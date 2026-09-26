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

        // QQ 音乐刻录功能开发中，暂不开放
        this.tips = Component.literal("QQ音乐刻录功能开发中，请使用网易云音乐");
        MusicNetIntegration.LOGGER.info("[刻录机] QQ 刻录入口已关闭（开发中），mid={}", mid);
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
