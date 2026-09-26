/*
 * CDBurnerMenuScreenMixin.java
 *
 * 唱片刻录机界面增强（客户端）：
 * - 加「搜索」按钮，打开多平台搜索界面
 * - 实现 SearchResultHost：搜索结果把平台标识回填到歌曲输入框（筐）
 * - 拦截 handleCraftButton：当输入框为 qqmusic:{mid} 时按 QQ 音乐写入 CD；
 *   其余（网易云分享链接）交给原版制作流程，完全兼容。
 *
 * 借鉴 NetMusicCanNeedQQ（BSD-3-Clause，原作者 Yincmewy）的注入方式：
 * remap=false + 继承 AbstractContainerScreen + Shadow 目标字段。
 */
package com.flapdisplayplus.music.mixin;

import com.flapdisplayplus.music.MusicNetIntegration;
import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.flapdisplayplus.music.client.gui.MusicSearchScreen;
import com.flapdisplayplus.music.client.gui.SearchResultHost;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.compat.NetMusicCompat;
import com.flapdisplayplus.music.data.SongInfoData;
import com.flapdisplayplus.music.search.ActivePlatform;
import com.flapdisplayplus.music.search.qqmusic.QQMusicApi;
import com.flapdisplayplus.music.search.qqmusic.QQMusicSearchSource;
import com.flapdisplayplus.music.search.qqmusic.QqSearchCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CDBurnerMenuScreen.class, remap = false)
public abstract class CDBurnerMenuScreenMixin extends AbstractContainerScreen<AbstractContainerMenu>
        implements SearchResultHost {

    @Shadow
    private EditBox textField;
    @Shadow
    private Checkbox readOnlyButton;
    @Shadow
    private Component tips;

    protected CDBurnerMenuScreenMixin(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void netmusicdisplay$addSearchButton(CallbackInfo ci) {
        // 搜索按钮：放在「制作唱片」下方，留出空间给红字提示（tips 在制作唱片行下方显示）
        Button button = Button.builder(
                        Component.translatable("netmusicdisplay.gui.search.open"),
                        btn -> Minecraft.getInstance().setScreen(new MusicSearchScreen(this)))
                .bounds(this.getGuiLeft() + 7, this.getGuiTop() + 62, 50, 18)
                .build();
        this.addRenderableWidget(button);

        // 平台切换按钮：与搜索按钮同一行并排，只显示平台名（去掉"平台："前缀）
        Button platformToggle = Button.builder(
                        Component.literal(ActivePlatform.isQq() ? "QQ音乐" : "网易云"),
                        btn -> {
                            ActivePlatform.toggle();
                            btn.setMessage(Component.literal(ActivePlatform.isQq() ? "QQ音乐" : "网易云"));
                        })
                .bounds(this.getGuiLeft() + 60, this.getGuiTop() + 62, 58, 18)
                .build();
        this.addRenderableWidget(platformToggle);
    }

    /** 把搜索结果标识写入歌曲输入框（网易云分享链接 / qqmusic:{mid}） */
    @Override
    public void netmusicdisplay$applySearchResult(String value) {
        if (this.textField != null) {
            this.textField.setValue(value);
        }
    }

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true)
    private void netmusicdisplay$handleCraftButton(CallbackInfo ci) {
        if (this.textField == null) {
            return;
        }
        String value = this.textField.getValue();
        // 非 QQ 标识：走网易云，交给原版制作流程。
        // 但原版 handleCraftButton 只认「纯数字 ID」(ID_REG=^\d{4,}$，matches 全串) 或
        // 「dj/数字」，任何完整链接都会直接提示「音乐ID错误」且不做唱片。
        // 所以这里先把输入规范化成原版认得的形式再放行——本注入在 HEAD 且不 cancel，
        // 原版随后读取 textField.getValue() 拿到的就是规范化后的值。
        if (value == null || !value.startsWith(QQMusicSearchSource.URL_PREFIX)) {
            netmusicdisplay$normalizeNetEaseInput(value);
            return;
        }
        ci.cancel();

        // 校验：必须持有空白唱片且非只读
        ItemStack cd = this.getMenu().getSlot(0).getItem();
        if (cd.isEmpty()) {
            this.tips = Component.literal("请先在左侧放入空白唱片");
            return;
        }
        if (netmusicdisplay$isReadOnly(cd)) {
            this.tips = Component.literal("该唱片为只读，无法刻录");
            return;
        }

        String mid = value.substring(QQMusicSearchSource.URL_PREFIX.length());
        if (mid.isBlank()) {
            this.tips = Component.literal("QQ 音乐 ID 为空");
            return;
        }

        // QQ 刻录已打通（2026-08-28）。流程：
        //   1. 先用搜索结果缓存（QqSearchCache）拿到歌名/时长/mediaMid，避免额外网络请求；
        //   2. 解析真实播放地址（vkey）必须在后台线程做——这是网络 IO，
        //      绝不能阻塞渲染/UI 线程（历史版本曾设计成在 Render 线程同步请求，已废弃）；
        //   3. 回主线程写 CD。
        // 若解析失败，说明该歌曲需要 VIP 且未登录（或已下架），给出明确提示。
        this.tips = Component.literal("正在解析 QQ 音乐...");
        MusicNetIntegration.LOGGER.info("[刻录机] 开始 QQ 刻录，mid={}", mid);

        final String songMid = mid;
        final boolean readOnly = netmusicdisplay$isReadOnlyFlag();

        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> netmusicdisplay$buildQqSongInfo(songMid, readOnly))
                .whenComplete((info, ex) -> Minecraft.getInstance().execute(() -> {
                    if (ex != null) {
                        MusicNetIntegration.LOGGER.error("[刻录机] QQ 解析异常 mid=" + songMid, ex);
                        this.tips = Component.literal("QQ 音乐解析失败：网络错误");
                        return;
                    }
                    if (info == null) {
                        // 未换到任何档位的播放地址：VIP 歌曲未登录，或歌曲已下架
                        this.tips = Component.literal("QQ 音乐解析失败：需要登录 QQ 或歌曲不可用");
                        return;
                    }
                    if (!NetMusicCompat.sendSongToServer(info)) {
                        this.tips = Component.literal("写入唱片失败");
                        return;
                    }
                    this.tips = Component.literal("已刻录：" + info.songName);
                    MusicNetIntegration.LOGGER.info("[刻录机] QQ 刻录成功：{}", info.songName);
                }));
    }

    /**
     * 组装 QQ 音乐的完整歌曲信息（在后台线程执行）。
     *
     * 【关键设计】写入 CD 的 songUrl 是**伪协议** `qqmusic:{songmid}`，不是真实播放地址。
     * 原因：
     *   1. 真实播放地址带 vkey 且有时效性，写死进 CD 会导致唱片过一段时间就失效；
     *   2. 模组已注册 QQMusicUrlResolver（IAsyncSongUrlResolver），播放时它会
     *      实时把 qqmusic:{mid} 换成带 vkey 的真实地址；
     *   3. 歌词侧 LyricCache 也只认 qqmusic:{songmid} 前缀来分发到 QQ 歌词接口。
     * 所以这里**必须先验证该歌曲确实能换到播放地址**（否则做出一张播不了的唱片），
     * 但验证完把真实地址丢掉，只写伪协议。
     *
     * 注意 songmid 与 media_mid 是两个不同的值：前者用于歌词/详情，
     * 后者仅用于拼 vkey 的 filename，不要混淆。
     */
    private SongInfoData netmusicdisplay$buildQqSongInfo(String songMid, boolean readOnly) {
        try {
            com.flapdisplayplus.music.search.SearchResult cached = QqSearchCache.get(songMid);
            String songName = cached != null ? cached.title() : null;
            int duration = cached != null ? cached.durationSec() : 0;
            String artist = cached != null ? cached.artist() : null;

            // 缓存缺歌名或时长时，同步补查一次（已在后台线程，安全）
            if (songName == null || songName.isBlank() || duration <= 0) {
                QQMusicApi.QQSong detail = QQMusicApi.getSongDetail(songMid);
                if (detail != null) {
                    if (songName == null || songName.isBlank()) {
                        songName = detail.title;
                    }
                    if (duration <= 0) {
                        duration = detail.durationSec;
                    }
                    if ((artist == null || artist.isBlank()) && detail.artist != null) {
                        artist = detail.artist;
                    }
                }
            }

            // 预检：确认这首歌当前能换到播放地址。换不到就不做唱片
            // （典型原因：VIP 歌曲未登录、歌曲已下架）。
            // 加超时防止后台线程被网络异常永久挂住（HttpUtil 自身 15s+15s）。
            String probeUrl = QQMusicApi.getPlayUrl(songMid)
                    .get(40, java.util.concurrent.TimeUnit.SECONDS);
            if (probeUrl == null || probeUrl.isBlank()) {
                MusicNetIntegration.LOGGER.info("[刻录机] QQ 歌曲无可用播放档位，拒绝刻录 mid={}", songMid);
                return null;
            }
            if (songName == null || songName.isBlank() || duration <= 0) {
                MusicNetIntegration.LOGGER.warn("[刻录机] QQ 歌曲元数据不完整 mid={} name={} duration={}",
                        songMid, songName, duration);
                return null;
            }

            SongInfoData data = new SongInfoData();
            data.songUrl = QQMusicSearchSource.URL_PREFIX + songMid; // 伪协议，不是真实地址
            data.songName = songName;
            data.songTime = duration;
            data.readOnly = readOnly;
            if (artist != null && !artist.isBlank()) {
                for (String a : artist.split("/")) {
                    if (!a.isBlank()) {
                        data.artists.add(a.trim());
                    }
                }
            }
            return data;
        } catch (Exception e) {
            MusicNetIntegration.LOGGER.error("[刻录机] 组装 QQ 歌曲信息失败 mid=" + songMid, e);
            return null;
        }
    }

    /** 读取「只读唱片」勾选框状态 */
    private boolean netmusicdisplay$isReadOnlyFlag() {
        try {
            return this.readOnlyButton != null && this.readOnlyButton.selected();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把网易云的各种输入形式规范化成原版制作按钮认得的形式，并写回输入框。
     *
     * 原版 CDBurnerMenuScreen.handleCraftButton 仅接受两种输入：
     *   ID_REG    = ^\d{4,}$      （matches 全串匹配，即纯数字歌曲 ID）
     *   DJ_ID_REG = ^dj/(\d+)$    （电台节目）
     * 它另外定义的四个 URL_*_REG 在该方法里从未被读取（死代码），
     * 所以完整分享链接一定匹配失败 → 提示「音乐ID错误」→ 做不出唱片。
     */
    private void netmusicdisplay$normalizeNetEaseInput(String raw) {
        if (raw == null || this.textField == null) {
            return;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return;
        }
        // 已是原版认得的形式：仅在有多余空白时写回
        if (s.matches("^\\d{4,}$") || s.matches("^dj/\\d+$")) {
            if (!s.equals(raw)) {
                this.textField.setValue(s);
            }
            return;
        }

        String fixed = null;
        // 电台/节目链接 → dj/数字
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("music\\.163\\.com/(?:#/)?(?:dj|program)\\?id=(\\d+)").matcher(s);
        if (m.find()) {
            fixed = "dj/" + m.group(1);
        }
        // 歌曲链接（含 #/song、song/media/outer/url 外链）→ 纯数字
        if (fixed == null) {
            m = java.util.regex.Pattern
                    .compile("music\\.163\\.com/(?:#/)?song(?:/media/outer/url)?\\?id=(\\d+)").matcher(s);
            if (m.find()) {
                fixed = m.group(1);
            }
        }
        // 兜底：任意含 id=数字 的参数形式
        if (fixed == null) {
            m = java.util.regex.Pattern.compile("[?&]id=(\\d+)").matcher(s);
            if (m.find()) {
                fixed = m.group(1);
            }
        }

        if (fixed != null && !fixed.equals(raw)) {
            MusicNetIntegration.LOGGER.info("[刻录机] 网易云输入已规范化：{} -> {}", raw, fixed);
            this.textField.setValue(fixed);
        }
    }

    private boolean netmusicdisplay$isReadOnly(ItemStack stack) {
        // 1.21.1：ItemStack 标签改为 DataComponent CUSTOM_DATA 存储
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return false;
        }
        CompoundTag tag = customData.copyTag();
        if (!tag.contains("NetMusicSongInfo", Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag infoTag = tag.getCompound("NetMusicSongInfo");
        return infoTag.getBoolean("read_only");
    }
}
