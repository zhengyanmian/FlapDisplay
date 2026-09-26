/*
 * CuckooClockMediaScreen.java
 *
 * 布谷鸟时钟媒体配置界面（客户端）。
 *
 * 继承 Create 的 AbstractSimiScreen（原版 DisplayLinkScreen 的父类），
 * 完全复用 Create 的 GUI 渲染管线：
 * - prepareFrame() 处理背景、坐标变换
 * - renderWindow() 在 Create 窗口坐标系内绘制内容
 * 这样渲染层级与原版界面一致，不会出现模糊层盖住自定义面板的问题。
 *
 * 数据流：选择图片 → 「应用」→ 发 SetMediaPacket 给服务端 →
 * 服务端写布谷鸟时钟 NBT → 显示链接器刷新 → 同步到客户端翻牌。
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.api.CuckooClockMedia;
import com.flapdisplayplus.music.MusicNetIntegration;
import com.flapdisplayplus.net.MediaResolverManager;
import com.flapdisplayplus.net.NetMediaManager;
import com.flapdisplayplus.network.SetMediaPacket;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class CuckooClockMediaScreen extends AbstractSimiScreen {

    // 扩展名清单统一由 MediaManager 维护（图片含 webp；视频仅 jcodec 真能解的 mp4/m4v/mov，
    // avi 因 jcodec 无 demuxer 已移除，避免出现"列表能选、实际必失败"的格式）
    private static final String[] EXTENSIONS = MediaManager.supportedExtensions();

    private final BlockPos cuckooPos;
    private final List<File> mediaFiles = new ArrayList<>();
    /** 通过「添加链接」加入的网络媒体（保存用户粘贴的原始链接） */
    private final List<String> netItems = new ArrayList<>();
    /** 网格展示用的统一条目：本地文件与网络链接混排，共用一套渲染/点击逻辑 */
    private final List<Entry> entries = new ArrayList<>();
    /** 链接输入框 */
    private net.minecraft.client.gui.components.EditBox urlBox;
    /** yt-dlp 可用性检测结果缓存（null=尚未检测） */
    private Boolean ytdlpAvailable;
    private String selectedPath = "";
    private String displayMode = "FIT";   // 默认保持比例完整显示（不强制拉伸），可切换 STRETCH/COVER
    private int page = 0;
    private final int perPage = 6;
    private String status = "";
    /** 内容源类型（CuckooClockMedia.SOURCE_*，默认图片媒体） */
    private String sourceType = CuckooClockMedia.SOURCE_IMAGE;

    /**
     * 内容类型选项卡（图片模式为默认，不设选项卡；点击已选中的选项卡可取消回图片）。
     * 顺序即显示顺序。
     */
    private static final String[] TYPES = {
            CuckooClockMedia.SOURCE_INFO_TIME,
            CuckooClockMedia.SOURCE_INFO_GAME_TIME,
            CuckooClockMedia.SOURCE_INFO_WEATHER,
            CuckooClockMedia.SOURCE_INFO_TPS
    };
    private static final String[] TYPE_LABELS = {"现实时间", "游戏时间", "天气", "TPS"};
    private net.minecraft.client.gui.components.Button[] typeTabs;

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 240;

    public CuckooClockMediaScreen(BlockPos cuckooPos) {
        super(Component.literal("翻牌万象 · 布谷鸟时钟媒体"));
        this.cuckooPos = cuckooPos;
        // 读取布谷鸟时钟当前配置（客户端侧），让 GUI 打开时能回显
        boolean ifaceOk = false;
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(cuckooPos);
        if (be instanceof CuckooClockMedia cuckoo) {
            ifaceOk = true;
            String path = cuckoo.flapdisplayplus$getMediaPath();
            String mode = cuckoo.flapdisplayplus$getDisplayMode();
            String st = cuckoo.flapdisplayplus$getSourceType();
            if (path != null && !path.isEmpty()) {
                this.selectedPath = path;
                this.displayMode = mode == null || mode.isEmpty() ? "FIT" : mode;
            }
            this.sourceType = (st == null || st.isEmpty()) ? CuckooClockMedia.SOURCE_IMAGE : st;
        }
        FlapDisplayPlus.LOGGER.info("[MediaGUI] 媒体界面已打开 clock={} 接口生效={} 当前媒体={}",
                cuckooPos, ifaceOk, selectedPath.isEmpty() ? "(空)" : selectedPath);
        scanMediaFiles();
        // 打开时按当前内容类型显示提示（回显的类型/图片/模式会在上方按钮与网格显示）
        status = sourceTypeHint(sourceType);
    }

    /**
     * 网格条目：本地文件或网络链接二选一。
     * key() 是选中值——本地文件用绝对路径，网络媒体用原始链接；
     * MediaManager 两者都认（网络链接会自动解析成本地缓存文件）。
     */
    private static final class Entry {
        final File file;
        final String url;

        private Entry(File file, String url) {
            this.file = file;
            this.url = url;
        }

        static Entry ofFile(File f) {
            return new Entry(f, null);
        }

        static Entry ofUrl(String u) {
            return new Entry(null, u);
        }

        boolean isNet() {
            return url != null;
        }

        String key() {
            return url != null ? url : file.getAbsolutePath();
        }

        /** 网格上的短显示名 */
        String shortName() {
            String n = name();
            return n.length() > 12 ? n.substring(0, 10) + ".." : n;
        }

        String name() {
            if (url != null) {
                // 有解析结果用标题，否则退化为链接尾段
                NetMediaManager.Task t = NetMediaManager.get(url);
                if (t != null && t.resolved != null && t.resolved.title != null
                        && !t.resolved.title.isEmpty()) {
                    return t.resolved.title;
                }
                String s = url;
                int q = s.indexOf('?');
                if (q > 0) {
                    s = s.substring(0, q);
                }
                int slash = s.lastIndexOf('/');
                return slash >= 0 && slash < s.length() - 1 ? s.substring(slash + 1) : s;
            }
            return file.getName();
        }
    }

    /** 重建网格条目（本地文件在前，网络链接在后） */
    private void rebuildEntries() {
        entries.clear();
        for (File f : mediaFiles) {
            entries.add(Entry.ofFile(f));
        }
        for (String u : netItems) {
            entries.add(Entry.ofUrl(u));
        }
    }

    /** 扫描 flap-media 目录（游戏根目录下） */
    private void scanMediaFiles() {
        mediaFiles.clear();
        File dir = new File(Minecraft.getInstance().gameDirectory, "flap-media");
        if (!dir.isDirectory()) {
            status = "未找到 flap-media 目录（请新建于游戏根目录）";
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            status = "flap-media 目录为空";
            return;
        }
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            for (String ext : EXTENSIONS) {
                if (name.endsWith(ext)) {
                    mediaFiles.add(f);
                    break;
                }
            }
        }
        mediaFiles.sort(Comparator.comparing(File::getName));
        rebuildEntries();
        status = "共 " + mediaFiles.size() + " 个媒体文件";
    }

    /** 添加网络链接：触发后台解析 + 下载，并立即选中 */
    private void addNetworkUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            status = "请先粘贴链接";
            return;
        }
        if (!MediaResolverManager.isNetworkInput(url)) {
            status = "只支持 http(s) 开头的链接";
            return;
        }
        if (netItems.contains(url)) {
            status = "该链接已在列表中";
            return;
        }
        netItems.add(url);
        rebuildEntries();
        selectedPath = url;
        // 幂等：已有任务不会重复下载
        NetMediaManager.acquire(url);
        boolean hasYtDlp = isYtDlpAvailable();
        status = hasYtDlp
                ? "已添加，正在解析/下载…"
                : "已添加。直链可直接用；视频网站链接需要 yt-dlp（未检测到，见日志提示）";
    }

    /** yt-dlp 可用性（结果缓存，避免每次点按钮都启动探测进程） */
    private boolean isYtDlpAvailable() {
        if (ytdlpAvailable == null) {
            ytdlpAvailable = MediaResolverManager.isYtDlpAvailable();
            FlapDisplayPlus.LOGGER.info("[MediaGUI] yt-dlp 可用={}", ytdlpAvailable);
        }
        return ytdlpAvailable;
    }

    /** 获取 flap-media 目录（游戏根目录下，不存在则创建） */
    private File getMediaDir() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "flap-media");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    @Override
    protected void init() {
        // 底部多出一行：链接输入（第三行），故窗口高度比原先 +22
        this.setWindowSize(PANEL_W + 20, PANEL_H + 112);
        super.init();
        this.clearWidgets();
        int cx = this.guiLeft + this.windowWidth / 2;
        int top = this.guiTop;
        int row1 = top + PANEL_H + 22;
        int row2 = top + PANEL_H + 44;
        int row3 = top + PANEL_H + 66;

        // ===== 顶部：内容类型选项卡（现实时间 / 游戏时间 / 天气 / TPS）=====
        // 图片模式为默认（无选项卡）；点击某选项卡切换，再点一次取消回图片。
        // 打开界面时全部灰色，当前类型在状态行显示。
        typeTabs = new net.minecraft.client.gui.components.Button[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) {
            final String t = TYPES[i];
            net.minecraft.client.gui.components.Button b = FdpButton.create(
                    this.guiLeft + 10 + i * 66, top + 18, 64, 20,
                    Component.literal("§7" + TYPE_LABELS[i]),
                    btn -> {
                        if (sourceType.equals(t)) {
                            // 再点一次：取消，回到默认图片模式
                            sourceType = CuckooClockMedia.SOURCE_IMAGE;
                            setTabSelected(null);
                            status = sourceTypeHint(CuckooClockMedia.SOURCE_IMAGE);
                        } else {
                            sourceType = t;
                            setTabSelected(t);
                            status = sourceTypeHint(t);
                        }
                    });
            typeTabs[i] = b;
            this.addRenderableWidget(b);
        }

        // ===== 第一行：文件夹工具 + 翻页 =====
        this.addRenderableWidget(FdpButton.create(cx - 169, row1, 110, 20,
                Component.literal("打开文件夹"), b -> {
                    File dir = getMediaDir();
                    if (!dir.isDirectory()) {
                        status = "无法创建 flap-media 目录";
                        return;
                    }
                    Util.getPlatform().openFile(dir);
                    status = "已打开文件夹：" + dir.getAbsolutePath();
                }));

        this.addRenderableWidget(FdpButton.create(cx - 53, row1, 70, 20,
                Component.literal("刷新"), b -> {
                    scanMediaFiles();
                    page = 0;
                    status = "已刷新：" + mediaFiles.size() + " 个媒体文件";
                }));

        this.addRenderableWidget(FdpButton.create(cx + 23, row1, 70, 20,
                Component.literal("◀ 上一页"), b -> {
                    if (page > 0) { page--; }
                }));

        this.addRenderableWidget(FdpButton.create(cx + 99, row1, 70, 20,
                Component.literal("下一页 ▶"), b -> {
                    int totalPages = Math.max(1, (mediaFiles.size() + perPage - 1) / perPage);
                    if (page < totalPages - 1) { page++; }
                }));

        // ===== 第二行：模式 / 清空 / 声音 / 应用 / 关闭 =====
        this.addRenderableWidget(FdpButton.create(cx - 165, row2, 90, 20,
                Component.literal("模式: " + modeLabel(displayMode)), b -> {
                    if (displayMode.equals("FIT")) displayMode = "STRETCH";
                    else if (displayMode.equals("STRETCH")) displayMode = "COVER";
                    else displayMode = "FIT";
                    b.setMessage(Component.literal("模式: " + modeLabel(displayMode)));
                }));

        this.addRenderableWidget(FdpButton.create(cx - 71, row2, 50, 20,
                Component.literal("清空"), b -> {
                    selectedPath = "";
                    status = "已清空（翻牌恢复原版显示）";
                }));

        // 视频声音开关（全局配置，写入 media.videoSound；切换后播放器重启生效）
        boolean sndOn = MediaManager.isVideoSoundEnabled();
        this.addRenderableWidget(FdpButton.create(cx - 17, row2, 52, 20,
                Component.literal((sndOn ? "§e" : "§7") + "声音: " + (sndOn ? "开" : "关")), b -> {
                    boolean nv = !MediaManager.isVideoSoundEnabled();
                    MediaManager.setVideoSound(nv);
                    b.setMessage(Component.literal((nv ? "§e" : "§7") + "声音: " + (nv ? "开" : "关")));
                    status = nv ? "视频将带声音播放（正在播放的已重启）" : "视频将静音播放";
                }));

        this.addRenderableWidget(FdpButton.create(cx + 39, row2, 66, 20,
                Component.literal("✔ 应用"), b -> apply()));

        this.addRenderableWidget(FdpButton.create(cx + 109, row2, 56, 20,
                Component.literal("✖ 关闭"), b -> this.onClose()));

        // ===== 第三行：网络链接输入 =====
        // 直链（图床/对象存储/CDN）直接下载；视频网站链接交给用户本机的 yt-dlp 解析。
        urlBox = new net.minecraft.client.gui.components.EditBox(
                this.font, cx - 169, row3, 232, 20, Component.literal(""));
        urlBox.setMaxLength(2048);
        urlBox.setHint(Component.literal("§7粘贴 http(s) 图片/视频链接"));
        this.addRenderableWidget(urlBox);

        this.addRenderableWidget(FdpButton.create(cx + 69, row3, 96, 20,
                Component.literal("添加链接"), b -> {
                    String v = urlBox.getValue();
                    addNetworkUrl(v);
                    urlBox.setValue("");
                }));
    }

    /** 高亮指定选项卡（金色），其余灰色 */
    private void setTabSelected(String t) {
        if (typeTabs == null) {
            return;
        }
        for (int i = 0; i < typeTabs.length; i++) {
            boolean sel = t != null && t.equals(TYPES[i]);
            typeTabs[i].setMessage(Component.literal((sel ? "§e" : "§7") + TYPE_LABELS[i]));
        }
    }

    /** 内容类型切换时的状态提示 */
    private String sourceTypeHint(String t) {
        return switch (t) {
            case CuckooClockMedia.SOURCE_INFO_TIME -> "翻牌将显示现实时间（日期 + 时分秒）";
            case CuckooClockMedia.SOURCE_INFO_GAME_TIME -> "翻牌将显示游戏内时间（如 06:00）";
            case CuckooClockMedia.SOURCE_INFO_WEATHER -> "翻牌将显示游戏内天气";
            case CuckooClockMedia.SOURCE_INFO_TPS -> "翻牌将显示服务器 TPS";
            default -> "在下方网格选择图片/GIF 后应用";
        };
    }

    private void apply() {
        if (CuckooClockMedia.SOURCE_IMAGE.equals(sourceType) && selectedPath.isEmpty()) {
            status = "图片模式请先选择一张图片";
            return;
        }
        PacketDistributor.sendToServer(new SetMediaPacket(cuckooPos, selectedPath, displayMode, sourceType));
        String pickedName = MediaResolverManager.isNetworkInput(selectedPath)
                ? pickedNetName(selectedPath)
                : new File(selectedPath).getName();
        status = "已应用：" + sourceTypeLabel(sourceType)
                + (CuckooClockMedia.SOURCE_IMAGE.equals(sourceType) ? " " + pickedName : "");
        this.onClose();
    }

    private String sourceTypeLabel(String t) {
        return switch (t) {
            case CuckooClockMedia.SOURCE_INFO_TIME -> "现实时间";
            case CuckooClockMedia.SOURCE_INFO_GAME_TIME -> "游戏时间";
            case CuckooClockMedia.SOURCE_INFO_WEATHER -> "天气";
            case CuckooClockMedia.SOURCE_INFO_TPS -> "TPS";
            default -> "图片";
        };
    }

    /** 网络媒体在状态栏的显示名（解析成功用标题，否则截断链接） */
    private String pickedNetName(String url) {
        NetMediaManager.Task t = NetMediaManager.get(url);
        if (t != null && t.resolved != null) {
            return t.resolved.displayName();
        }
        return url.length() > 40 ? url.substring(0, 38) + "…" : url;
    }

    private String modeLabel(String m) {
        return switch (m) {
            case "STRETCH" -> "拉伸铺满";
            case "COVER" -> "裁切铺满";
            default -> "完整显示";
        };
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = this.guiLeft;
        int top = this.guiTop;

        // 木质面板
        graphics.fill(left - 4, top - 4, left + PANEL_W + 4, top + PANEL_H + 4, 0xFF2A1D13);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF3D2B1F);
        graphics.hLine(left, left + PANEL_W - 1, top, 0xFF5C4430);
        graphics.hLine(left, left + PANEL_W - 1, top + PANEL_H, 0xFF5C4430);
        graphics.vLine(left, top, top + PANEL_H, 0xFF5C4430);
        graphics.vLine(left + PANEL_W, top, top + PANEL_H, 0xFF5C4430);

        graphics.drawCenteredString(this.font, "翻牌万象 · 布谷鸟时钟媒体", left + PANEL_W / 2, top + 8, 0xFFE8D5AB);

        // 选项卡占 top+18~38；状态提示居中显示在选项卡下方
        graphics.drawCenteredString(this.font, status, left + PANEL_W / 2, top + 44, 0xFFC9A86A);

        // 网格：3 列 × 2 行，每格 92x70（缩略图 + 文件名）
        int gridLeft = left + 10;
        int gridTop = top + 56;
        int cellW = 92;
        int cellH = 70;
        int cols = 3;
        int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * perPage;

        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= entries.size()) break;
            Entry e = entries.get(idx);
            int col = i % cols;
            int row = i / cols;
            int x = gridLeft + col * (cellW + 6);
            int y = gridTop + row * (cellH + 8);
            boolean selected = e.key().equals(selectedPath);
            int borderColor = selected ? 0xFFC9A86A : 0xFF5C4430;
            graphics.fill(x, y, x + cellW, y + cellH, 0xFF2F2216);
            graphics.hLine(x, x + cellW, y, borderColor);
            graphics.hLine(x, x + cellW, y + cellH, borderColor);
            graphics.vLine(x, y, y + cellH, borderColor);
            graphics.vLine(x + cellW, y, y + cellH, borderColor);

            // ===== 缩略图区（上部 48px）：静态图显示图片，GIF 显示当前动画帧 =====
            String path = e.key();
            ResourceLocation frame = MediaManager.getFrame(path, System.currentTimeMillis());
            int iw = MediaManager.getTextureWidth(path);
            int ih = MediaManager.getTextureHeight(path);
            if (frame != null && iw > 0 && ih > 0) {
                float ratio = Math.min((cellW - 8f) / iw, 44f / ih);
                int dw = Math.max(1, (int) (iw * ratio));
                int dh = Math.max(1, (int) (ih * ratio));
                graphics.blit(frame, x + 4 + (cellW - 8 - dw) / 2, y + 2 + (44 - dh) / 2,
                        dw, dh, 0, 0, iw, ih, iw, ih);
            } else if (e.isNet()) {
                // 网络媒体：下载/解析未就绪时显示进度，而不是无意义的省略号
                NetMediaManager.Task t = NetMediaManager.get(e.url);
                String s = t == null ? "准备中" : t.statusText();
                if (s.length() > 13) {
                    s = s.substring(0, 12) + "…";
                }
                int c = (t != null && t.state == NetMediaManager.State.FAILED) ? 0xFFE06C6C : 0xFFB09A72;
                graphics.drawCenteredString(this.font, s, x + cellW / 2, y + 18, c);
            } else {
                graphics.drawCenteredString(this.font, "…", x + cellW / 2, y + 18, 0xFFB09A72);
            }

            // ===== 文件名（下部）：名称 + 类型/状态 =====
            graphics.drawCenteredString(this.font, e.shortName(), x + cellW / 2, y + 52, 0xFFE8D5AB);
            String sub;
            if (e.isNet()) {
                NetMediaManager.Task t = NetMediaManager.get(e.url);
                if (t != null && t.state == NetMediaManager.State.FAILED) {
                    sub = "失败";
                } else if (t != null && t.state == NetMediaManager.State.READY) {
                    sub = "网络";
                } else {
                    sub = t == null ? "网络" : t.statusText();
                }
                if (sub.length() > 13) {
                    sub = sub.substring(0, 12) + "…";
                }
            } else {
                sub = getExt(e.file).toUpperCase(Locale.ROOT);
            }
            graphics.drawCenteredString(this.font, sub, x + cellW / 2, y + 60, 0xFFB09A72);
        }

        graphics.drawCenteredString(this.font, "第 " + (page + 1) + " / " + totalPages + " 页",
                left + PANEL_W / 2, top + PANEL_H - 14, 0xFFB09A72);
    }

    private String getExt(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : "";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = this.guiLeft + 10;
            int top = this.guiTop + 76;
            int cellW = 92;
            int cellH = 70;
            int cols = 3;
            int start = page * perPage;
            for (int i = 0; i < perPage; i++) {
                int idx = start + i;
                if (idx >= entries.size()) break;
                int col = i % cols;
                int row = i / cols;
                int x = left + col * (cellW + 6);
                int y = top + row * (cellH + 8);
                if (mouseX >= x && mouseX <= x + cellW && mouseY >= y && mouseY <= y + cellH) {
                    Entry e = entries.get(idx);
                    selectedPath = e.key();
                    status = "已选择：" + e.name();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
