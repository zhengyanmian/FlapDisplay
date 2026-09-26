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
import org.spongepowered.asm.mixin.Unique;
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
        netmusicdisplay$raiseMaxLength("init");

        // 搜索按钮：放在「制作唱片」下方，留出空间给红字提示（tips 在制作唱片行下方显示）
        //
        // 【踩坑记录，勿改回 translatable】这里曾经写成
        //   Component.translatable("netmusicdisplay.gui.search.open")
        // 键名前缀用了 Net Music 的命名空间，而我们的语言文件里的键是
        //   flapdisplayplus.gui.search.open
        // 键对不上时 Minecraft 找不到翻译，会**直接把原始键名当按钮文字显示**，
        // 于是按钮上出现一长串 "netmusicdisplay.gui.search.open"，不是「搜索」。
        // 现在直接用字面量，绕开语言文件这一层（中文界面下本来也不需要多语言）。
        Button button = Button.builder(
                        Component.literal("搜索"),
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

    /**
     * 【致命坑，勿删】把输入框上限从原版的 19 抬到 512。
     *
     * 背景：Net Music 的 CDBurnerMenuScreen.init() 里对输入框调了 setMaxLength(19)。
     * 19 是按「网易云纯数字 ID」定的，而 QQ 音乐的标识是 `qqmusic:{songmid}`。
     *
     * ★ songmid 实测**不全是 14 位**（2026-09-27 实测发现）：形如
     *   `002D2rns2pbPcm`（14 位）也是合法 songmid，vkey 换地址 result=0 有 purl；
     *   把尾部 `Pcm` 去掉反而变 101404 不存在。所以不能假设固定长度，
     *   只能靠抬高上限来容纳。
     *
     * ★ 为什么必须在 init 之外**再**注入 resize：见 netmusicdisplay$onResize 的注释。
     *   光在 init 尾部抬上限只能撑到下一次界面重建，重建后值就被截。
     */
    private void netmusicdisplay$raiseMaxLength(String from) {
        if (this.textField == null) {
            MusicNetIntegration.LOGGER.error("[刻录机] {} 抬高上限失败：textField 为 null", from);
            return;
        }
        this.textField.setMaxLength(512);
        MusicNetIntegration.LOGGER.info("[刻录机] {} 已把输入框上限抬到 512（当前值长度={}）",
                from, this.textField.getValue() == null ? -1 : this.textField.getValue().length());
    }

    /**
     * 【终极修复 2026-09-27 三次】回填的完整值被界面重建截断后，**自己把值补回来**。
     *
     * ★ 为什么前三轮「抬上限」全都治不好 —— 原因是顺序，不是值：
     *   `EditBox.setMaxLength(n)` **只写 maxLength 字段，不会重新处理已存的值**；
     *   真正截断发生在 `setValue()` 内部的 `substring(0, maxLength)`。
     *   而 Net Music 的 `init()` 里固定是「先 setValue(旧值)、后 setMaxLength(19)」，
     *   我们的注入又在整个方法 RETURN 之后才跑 →
     *   **等我们能抬上限时，值早就被切完了，抬上限只能救「下一次写入」。**
     *
     * ★ 实测证据（04:22:08 一组日志）：
     *     08.305 回填 22 位 → 实际存入 22 位     ← 写入成功
     *     08.307 init 注入：当前值长度=19        ← 仅 2ms 后重建，值被重切
     *     13.036 点制作 → mid 仅 11 位           ← 你看到的就是这串
     *   19 = `qqmusic:`(8) + 11，末尾 `Pcm` 被切掉。
     *
     * ★ 所以不能再依赖「上限」这条脆弱路径，改为**记住完整值、被截断就补回**：
     *   ① 回填时把完整值记进 pending（见 netmusicdisplay$applySearchResult）
     *   ② 每次进入 init（@At("HEAD")，**早于原版 setValue/setMaxLength**）就尝试补值
     *   ③ 用「前缀 + 已存值是完整值的前缀 + 已存值确实比完整值短」三重条件判定，
     *      只有确认是「被截断」才补，不会覆盖用户手工输入的其他内容
     *   ④ 补成功即清空 pending（一次性），避免每次打开界面都强行回写
     *
     * @return 是否实际补写了值
     */
    private boolean netmusicdisplay$restoreTruncatedValue(String from) {
        String want = this.netmusicdisplay$pendingValue;
        if (want == null || this.textField == null) {
            return false;
        }
        String have = this.textField.getValue();
        if (have == null) {
            have = "";
        }
        // 已经完整了（重建时上限够大所以没被切）→ 清 pending，收工
        if (have.equals(want)) {
            this.netmusicdisplay$pendingValue = null;
            MusicNetIntegration.LOGGER.info("[刻录机] {} 值已是完整的 {} 位，无需补写", from, want.length());
            return false;
        }
        // 只补「明显是它被截断的前缀」的情况，避免动用户的正常输入
        boolean looksTruncated = want.startsWith(have) && have.length() < want.length();
        if (!looksTruncated) {
            this.netmusicdisplay$pendingValue = null;
            MusicNetIntegration.LOGGER.info(
                    "[刻录机] {} 当前值 '{}'（{} 位）与待补值无关，放弃补写",
                    from, have, have.length());
            return false;
        }
        this.textField.setMaxLength(512);
        this.textField.setValue(want);
        String after = this.textField.getValue();
        this.netmusicdisplay$pendingValue = null;
        MusicNetIntegration.LOGGER.info(
                "[刻录机] {} 补回被截断的值：'{}'（{} 位）→ '{}'（{} 位）",
                from, have, have.length(), after, after == null ? -1 : after.length());
        return true;
    }

    /** 待补写的完整回填值（被界面重建截断时用来补回）；null 表示无需补 */
    @Unique
    private String netmusicdisplay$pendingValue;

    /**
     * 【关键修复 2026-09-27 四次】把「补值」提前到 init 的 HEAD。
     *
     * 必须 HEAD 而不是 RETURN：原版 init 里是
     *   ① 存下旧值 ② new EditBox（上限=默认 32）③ setValue(旧值) ④ setMaxLength(19)
     * 只有在我们先补一次（把上限抬起来）之后，后续 ③ 的 setValue 才不会被切。
     * RETURN 注入只能事后补救，而 setMaxLength 不会让被切的字符长回来。
     */
    @Inject(method = "init", at = @At("HEAD"))
    private void netmusicdisplay$restoreOnInit(CallbackInfo ci) {
        // 先无条件抬上限：此时 textField 可能还是上一轮的实例（原版 9-24 行的保存分支要用它），
        // 也可能是 null（首次打开）。抬了不亏，下面补值还会再抬一次。
        if (this.textField != null) {
            this.textField.setMaxLength(512);
        }
        netmusicdisplay$restoreTruncatedValue("init(HEAD)");
    }

    /**
     * resize 同样先补值再让原版继续。
     *
     * 注意 `CDBurnerMenuScreen.resize` 的实现是「存旧值 → super.resize → setValue(旧值)」，
     * 若我们只在 RETURN 补，的确也能修好（它是 setValue 之后），
     * 但 HEAD 补的成本一样、且能顺带抬高上限，让原版自己那次 setValue 就不被切。
     */
    @Inject(method = "resize", at = @At("HEAD"))
    private void netmusicdisplay$restoreOnResize(Minecraft mc, int width, int height, CallbackInfo ci) {
        if (this.textField != null) {
            this.textField.setMaxLength(512);
        }
    }

    /** 原 RETURN 注入保留：兜底抬上限（见下） */
    @Inject(method = "resize", at = @At("RETURN"))
    private void netmusicdisplay$onResize(Minecraft mc, int width, int height, CallbackInfo ci) {
        netmusicdisplay$raiseMaxLength("resize");
        netmusicdisplay$restoreTruncatedValue("resize(RETURN)");
    }

    /**
     * 【修复截断的核心】拿疑似被截断的 mid 去搜索缓存反查完整值。
     *
     * 为什么这是终解：截断**必然**产生「完整值的前缀」，所以前缀匹配一定能命中，
     * 不需要猜长度、不需要猜阈值、也不依赖任何「上限抬没抬起来」的假设。
     *
     * @return 完整 songmid；无法补全时返回 null
     */
    private static String netmusicdisplay$healMidFromCache(String mid) {
        try {
            return QqSearchCache.findFullMidByPrefix(mid);
        } catch (Throwable t) {
            MusicNetIntegration.LOGGER.error("[刻录机] 缓存反查失败 mid=" + mid, t);
            return null;
        }
    }

    /**
     * 是否「看起来像被截断」。仅用于**给用户提示**，不用于拦截流程
     * （合法的 songmid 实测 14 位，但长度不是可靠判据，所以这里只是启发式）。
     */
    private static boolean netmusicdisplay$looksTruncated(String mid) {
        return mid != null && mid.length() < 12;
    }


    /** 把搜索结果标识写入歌曲输入框（网易云分享链接 / qqmusic:{mid}） */
    @Override
    public void netmusicdisplay$applySearchResult(String value) {
        if (this.textField == null) {
            MusicNetIntegration.LOGGER.error("[刻录机] 回填失败：textField 为 null");
            return;
        }
        // ★ 先记下完整值：界面重建会重跑 init()，那时 setValue(旧值) 可能被 19 位上限截断。
        //   有了这个 pending，init(HEAD) 注入就能把被切掉的部分补回来（见 restoreTruncatedValue）。
        this.netmusicdisplay$pendingValue = value;
        // 写入前后各记一次长度：写入前是"应该写进去的"，写入后是"实际存下来的"。
        // 两者不一致即证明截断发生在 setValue 内部（maxLength 仍太小），
        // 而不是发生在别处——这条日志能一眼区分故障位置。
        MusicNetIntegration.LOGGER.info("[刻录机] 回填输入框：待写入 '{}'（{} 位）",
                value, value == null ? -1 : value.length());
        this.textField.setMaxLength(512);
        this.textField.setValue(value);
        String after = this.textField.getValue();
        MusicNetIntegration.LOGGER.info("[刻录机] 回填结果：实际存入 '{}'（{} 位）",
                after, after == null ? -1 : after.length());
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
        // 【终局修复 2026-09-27 三次 —— 别再靠长度猜，靠缓存把值救回来】
        //
        // 前几版这里写的是 `mid.length() < 14` / `< 12` 报错，思路都是「用长度识别截断」。
        // 实测证明这条路走不通也不必要：
        //   · songmid 合法长度并非固定（`002D2rns2pbPcm` 14 位合法，去掉 `Pcm` 反而 101404）；
        //   · 被截断的值和真实短值靠长度区分不开；
        //   · **最关键：截断成的 11 位本身就是完整 songmid 的前缀**，而我们手上
        //     一定存着完整值（QqSearchCache 在搜索结果生成时就 put 了，
        //     QQMusicSearchSource 里还做了预填，保证不依赖单点调用）。
        //
        // 所以正确做法：**拿被截断的前缀去缓存里反查完整 mid**，查到就自动补全，
        // 用户完全无感。查不到（历史遗留值 / 手工输入）才提示。
        String healed = netmusicdisplay$healMidFromCache(mid);
        if (healed != null && !healed.equals(mid)) {
            MusicNetIntegration.LOGGER.warn(
                    "[刻录机] 检测到 mid 被截断：'{}'（{} 位）→ 已按搜索缓存补全为 '{}'（{} 位）",
                    mid, mid.length(), healed, healed.length());
            mid = healed;
            // 顺手把界面上的值也修好，避免用户重复踩
            if (this.textField != null) {
                this.textField.setMaxLength(512);
                this.textField.setValue(QQMusicSearchSource.URL_PREFIX + healed);
            }
        } else if (netmusicdisplay$looksTruncated(mid) && healed == null) {
            // 缓存里也没有 → 大概率是手工输入的残缺值，明确提示
            MusicNetIntegration.LOGGER.error(
                    "[刻录机] mid 过短（{} 位）'{}' 且缓存中查不到完整值，疑似手工输入的残缺 ID",
                    mid.length(), mid);
            this.tips = Component.literal("QQ ID 疑似不完整（仅 " + mid.length() + " 位），请用「搜索」重新选歌");
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
                        // 组装失败。真因可能是「元数据缺失」「歌曲不存在/已下架」
                        // 「VIP 歌曲未登录」等多种，具体原因已在 buildQqSongInfo
                        // 里按分支记了带原因标记的日志，这里不再猜——
                        // 历史上这里写死「需要登录 QQ」，把「mid 被输入框截断」
                        // 误报成登录问题，害得排查方向跑偏（见 init() 里的注释）。
                        this.tips = Component.literal("QQ 音乐解析失败：详情见日志（非必然是登录问题）");
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
            // （典型原因：VIP 歌曲未登录、歌曲已下架、或 mid 本身有误）。
            // 加超时防止后台线程被网络异常永久挂住（HttpUtil 自身 15s+15s）。
            String probeUrl = QQMusicApi.getPlayUrl(songMid)
                    .get(40, java.util.concurrent.TimeUnit.SECONDS);
            if (probeUrl == null || probeUrl.isBlank()) {
                // 换不到地址时，顺手探一次详情接口，把真因区分开再记日志，
                // 免得又归到「需要登录」这个万能借口上。
                String reason;
                QQMusicApi.QQSong detail = QQMusicApi.getSongDetail(songMid);
                if (detail == null) {
                    reason = "歌曲不存在或已下架（详情接口无此 mid）";
                } else {
                    boolean hasMeta = detail.title != null && !detail.title.isBlank() && detail.durationSec > 0;
                    if (!hasMeta) {
                        reason = "歌曲元数据不完整（歌名/时长为空）";
                    } else if (detail.vip && !com.flapdisplayplus.music.qq.QqCredentialManager.hasValidCredential()) {
                        reason = "VIP 歌曲且未登录（已登录可播放）";
                    } else if (detail.vip) {
                        reason = "VIP 歌曲且已登录，但当前账号无该曲版权";
                    } else {
                        reason = "免费歌曲却换不到地址，可能被版权方限制或接口限流，可稍后重试";
                    }
                }
                MusicNetIntegration.LOGGER.warn("[刻录机] QQ 歌曲无可用播放档位，拒绝刻录 mid={} 原因={}", songMid, reason);
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
