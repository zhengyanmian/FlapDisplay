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
import com.flapdisplayplus.client.web.WebScreenManager;
import com.flapdisplayplus.music.MusicNetIntegration;
import com.flapdisplayplus.net.MediaResolverManager;
import com.flapdisplayplus.net.NetMediaManager;
import com.flapdisplayplus.network.SetMediaPacket;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.createmod.catnip.gui.UIRenderHelper;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.flapdisplayplus.network.ModNetwork;
import net.minecraftforge.network.PacketDistributor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
    /** 右键删除的两步确认状态（8 秒内再右键同一格才真正删除；左键取消） */
    private String pendingDeleteKey;
    private long pendingDeleteAt;

    private final List<String> netItems = new ArrayList<>();
    /** 网格展示用的统一条目：本地文件与网络链接混排，共用一套渲染/点击逻辑 */
    private final List<Entry> entries = new ArrayList<>();
    /** 链接输入框 */
    private net.minecraft.client.gui.components.EditBox urlBox;
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
    private Button[] typeTabs;

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 240;

    /**
     * 媒体网格的几何参数。
     *
     * 【2026-08-28 重构】此前网格的左上角、格子尺寸、列数在 renderWindow() 与 mouseClicked()
     * 里**各自硬编码了一份**，且两处的 top 差了 20px（绘制用 guiTop+56，判定用 guiTop+76），
     * 导致「点击的格子比看到的低一行」。现在唯一来源就是这个 record，绘制与命中判定都调它，
     * 结构上不可能再漂移。
     */
    private record Grid(int left, int top, int cellW, int cellH, int cols, int gapX, int gapY) {
        int x(int i) { return left + (i % cols) * (cellW + gapX); }
        int y(int i) { return top + (i / cols) * (cellH + gapY); }

        boolean hit(int i, double mx, double my) {
            return mx >= x(i) && mx <= x(i) + cellW && my >= y(i) && my <= y(i) + cellH;
        }
    }

    /**
     * 网格几何的唯一定义处（缩略图区高 48 + 名称 2 行）。
     * 【2026-09-27 排版】网格在窗口内水平居中（此前贴左 left+10，右侧空出 62px）。
     */
    private Grid grid() {
        int cols = 3, cellW = 92, gapX = 6;
        int gridW = cols * cellW + (cols - 1) * gapX;
        int left = this.guiLeft + (this.windowWidth - gridW) / 2;
        return new Grid(left, this.guiTop + 56, cellW, 70, cols, gapX, 8);
    }

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
        loadNetItems();
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
                // 网页条目：显示主机名
                if (WebScreenManager.isWebPath(url)) {
                    String s = url.substring("web://".length());
                    int scheme = s.indexOf("://");
                    if (scheme >= 0) {
                        s = s.substring(scheme + 3);
                    }
                    int q = s.indexOf('?');
                    if (q > 0) {
                        s = s.substring(0, q);
                    }
                    int slash = s.indexOf('/');
                    return slash > 0 ? s.substring(0, slash) : s;
                }
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
        // 自动补全协议：粘贴 example.com/img.png 这类裸地址直接可用
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (!MediaResolverManager.isNetworkInput(url)) {
            status = "只支持 http(s) 链接";
            return;
        }
        if (netItems.contains(url)) {
            status = "该链接已在列表中";
            return;
        }
        netItems.add(url);
        saveNetItems();
        rebuildEntries();
        selectedPath = url;
        // 幂等：已有任务不会重复下载
        NetMediaManager.acquire(url);
        status = "已添加，正在解析/下载…";
    }

    /** 添加网页（web:// 前缀路由到 MCEF 离屏浏览器；可选前置，未装时仅提示不报错） */
    private void addWebUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) {
            status = "请先输入网页地址";
            return;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            // 自动补全协议：输入 baidu.com / www.xxx.com 直接可用，不必手打 https://
            u = "https://" + u;
        }
        String key = "web://" + u;
        if (netItems.contains(key)) {
            status = "该网页已在列表中";
            return;
        }
        netItems.add(key);
        saveNetItems();
        rebuildEntries();
        selectedPath = key;
        status = WebScreenManager.isMcefLoaded()
                ? "已添加网页，正在加载…（选中后再点一次可打开预览交互）"
                : "已添加网页。未安装 MCEF 前置，翻牌无法显示（Modrinth 搜索 mcef）";
    }

    /** 网络条目持久化文件（flap-media/net-list.txt，一行一条：链接或 web:// 网址） */
    private File getNetListFile() {
        return new File(getMediaDir(), "net-list.txt");
    }

    /** 启动时恢复上次添加的链接/网页（失败静默，视为空列表） */
    private void loadNetItems() {
        File f = getNetListFile();
        if (!f.isFile()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !netItems.contains(line)) {
                    netItems.add(line);
                }
            }
        } catch (IOException e) {
            FlapDisplayPlus.LOGGER.warn("[MediaGUI] 读取 net-list.txt 失败（忽略）: {}", e.toString());
        }
    }

    /** 新增链接/网页后立即落盘 */
    private void saveNetItems() {
        File f = getNetListFile();
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
            for (String s : netItems) {
                w.write(s);
                w.newLine();
            }
        } catch (IOException e) {
            FlapDisplayPlus.LOGGER.warn("[MediaGUI] 写入 net-list.txt 失败（忽略）: {}", e.toString());
        }
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
        typeTabs = new Button[TYPES.length];
        // 【2026-09-27 排版】选项卡整组居中（此前贴左 left+10，右侧空出 88px）
        int tabSpan = (TYPES.length - 1) * 66 + 64;
        int tabsLeft = this.guiLeft + (this.windowWidth - tabSpan) / 2;
        for (int i = 0; i < TYPES.length; i++) {
            FdpButton b = makeTabButton(tabsLeft + i * 66, top + 18, 64, 20,
                    TYPES[i], TYPE_LABELS[i]);
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

        // 【2026-09-27 修复】「清空」以前只把 GUI 里的 selectedPath 置空，**从不发包**，
        // 所以服务端布谷鸟时钟上仍保留着原媒体 → 翻牌照旧显示媒体，用户反馈「点击清空后无效」。
        // 而且「应用」当时还写了 `selectedPath.isEmpty() → 报错返回`，等于没有任何途径能清空。
        // 现在：清空 = 立即发包把服务端媒体置空，并顺手停掉本地视频播放器（即时生效，不用等重登）。
        this.addRenderableWidget(FdpButton.create(cx - 71, row2, 50, 20,
                Component.literal("清空"), b -> {
                    selectedPath = "";
                    sourceType = CuckooClockMedia.SOURCE_IMAGE;
                    setTabSelected(null);
                    ModNetwork.CHANNEL.sendToServer(new SetMediaPacket(
                            cuckooPos, "", displayMode, CuckooClockMedia.SOURCE_IMAGE));
                    // 不做本地即时清除：registry 的 key 是「显示带 controller 坐标」而非时钟坐标，
                    // 在这里按 cuckooPos 移除既可能打偏、又会误伤别的显示器。
                    // 服务端清除包会在一个 tick 内到达并精确移除；客户端还有一处
                    // 「方块已不存在就立即丢弃」的校验兜底（见 ClientWorldEvents.tick）。
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
        // 直链（图床/对象存储/CDN）直接下载；「网页」按钮走 MCEF 离屏浏览器（可选前置）。
        urlBox = new net.minecraft.client.gui.components.EditBox(
                this.font, cx - 169, row3, 172, 20, Component.literal(""));
        urlBox.setMaxLength(2048);
        urlBox.setHint(Component.literal("§7粘贴链接或网址（可省略 https://）"));
        this.addRenderableWidget(urlBox);

        this.addRenderableWidget(FdpButton.create(cx + 9, row3, 56, 20,
                Component.literal("网页"), b -> {
                    String v = urlBox.getValue();
                    addWebUrl(v);
                    urlBox.setValue("");
                }));

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

    /** 选项卡按钮（自绘风格，与其它按钮统一） */
    private FdpButton makeTabButton(int x, int y, int w, int h, String type, String label) {
        return FdpButton.create(x, y, w, h, Component.literal("§7" + label), btn -> {
            if (sourceType.equals(type)) {
                // 再点一次：取消，回到默认图片模式
                sourceType = CuckooClockMedia.SOURCE_IMAGE;
                setTabSelected(null);
                status = sourceTypeHint(CuckooClockMedia.SOURCE_IMAGE);
            } else {
                sourceType = type;
                setTabSelected(type);
                status = sourceTypeHint(type);
            }
        });
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
        // 【2026-09-27 修复】以前这里是 `selectedPath.isEmpty() → 报错返回`，
        // 导致「想清空媒体」根本发不出包（配合当时不发包的「清空」按钮 = 完全无法清除）。
        // 现在空路径是合法输入，语义就是「清除该翻牌上的媒体叠加，恢复原版字符显示」。
        boolean clearing = selectedPath == null || selectedPath.isEmpty();
        ModNetwork.CHANNEL.sendToServer(new SetMediaPacket(cuckooPos, selectedPath, displayMode, sourceType));
        if (clearing) {
            status = "已应用：清除媒体（恢复原版显示）";
            this.onClose();
            return;
        }
        String pickedName;
        if (WebScreenManager.isWebPath(selectedPath)) {
            pickedName = "网页";
        } else if (MediaResolverManager.isNetworkInput(selectedPath)) {
            pickedName = pickedNetName(selectedPath);
        } else {
            pickedName = new File(selectedPath).getName();
        }
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
        // 机械动力（Create）风格窗口：整个窗口（含按钮区）统一收进一个
        // 「深色内底 + 黄铜圆角边框」的 Simi 窗口，配色/拼法与 Create 自家
        // ValueSettingsScreen（扳手调值界面）完全一致。按钮控件本身不动。
        int w = this.windowWidth;
        int h = this.windowHeight;

        // 内底：Create value_settings.png 上 BAR_BG 单像素 (14,0,0)，即官方调值窗口的深底色
        graphics.fill(left, top, left + w, top + h, 0xFF0E0000);
        renderBrassFrame(graphics, left, top, w, h);

        FdpWidgets.centeredNote(graphics, "翻牌万象 · 布谷鸟时钟媒体",
                left + w / 2, top + 8, FdpWidgets.TEXT);

        // 选项卡占 top+18~38；状态提示居中显示在选项卡下方
        FdpWidgets.centeredNote(graphics, status, left + w / 2, top + 44, FdpWidgets.TEXT_HL);

        // 网格：3 列 × 2 行（缩略图 + 文件名）；几何来自 grid()，与命中判定共用
        Grid g = grid();
        int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * perPage;

        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= entries.size()) break;
            Entry e = entries.get(idx);
            int x = g.x(i);
            int y = g.y(i);
            int cellW = g.cellW();
            int cellH = g.cellH();
            boolean selected = e.key().equals(selectedPath);
            int borderColor = selected ? FdpWidgets.BORDER_HL : FdpWidgets.BORDER;
            graphics.fill(x, y, x + cellW, y + cellH, FdpWidgets.WOOD_CELL);
            FdpWidgets.outline(graphics, x, y, cellW, cellH, borderColor);

            // ===== 缩略图区（上部 48px）：静态图显示图片，GIF 显示当前动画帧 =====
            String path = e.key();
            // 【网页条目必须用只读帧】这里对每个条目取帧；若用会「创建浏览器」的 getFrame，
            // 打开/删除条目时就会把每个网页条目都现场加载一遍（= 「删一个网页，其他网页被刷新」）。
            ResourceLocation frame = WebScreenManager.isWebPath(path)
                    ? WebScreenManager.peekFrame(path)
                    : MediaManager.getFrame(path, System.currentTimeMillis());
            int iw = MediaManager.getTextureWidth(path);
            int ih = MediaManager.getTextureHeight(path);
            if (frame != null && iw > 0 && ih > 0) {
                float ratio = Math.min((cellW - 8f) / iw, 44f / ih);
                int dw = Math.max(1, (int) (iw * ratio));
                int dh = Math.max(1, (int) (ih * ratio));
                graphics.blit(frame, x + 4 + (cellW - 8 - dw) / 2, y + 2 + (44 - dh) / 2,
                        dw, dh, 0, 0, iw, ih, iw, ih);
            } else if (WebScreenManager.isWebPath(path)) {
                // 网页条目：纹理就绪前显示状态提示（就绪后走上面的 blit 分支实时镜像）
                String s = WebScreenManager.isMcefLoaded() ? "网页加载中…" : "需安装 MCEF";
                graphics.drawCenteredString(this.font, s, x + cellW / 2, y + 18, FdpWidgets.TEXT_DIM);
            } else if (e.isNet()) {
                // 网络媒体：下载/解析未就绪时显示进度，而不是无意义的省略号
                NetMediaManager.Task t = NetMediaManager.get(e.url);
                String s = t == null ? "准备中" : t.statusText();
                if (s.length() > 13) {
                    s = s.substring(0, 12) + "…";
                }
                int c = (t != null && t.state == NetMediaManager.State.FAILED)
                        ? FdpWidgets.TEXT_ERROR : FdpWidgets.TEXT_DIM;
                graphics.drawCenteredString(this.font, s, x + cellW / 2, y + 18, c);
            } else {
                graphics.drawCenteredString(this.font, "…", x + cellW / 2, y + 18, FdpWidgets.TEXT_DIM);
            }

            // ===== 文件名（下部）：名称 + 类型/状态 =====
            graphics.drawCenteredString(this.font, e.shortName(), x + cellW / 2, y + 52, FdpWidgets.TEXT);
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
            graphics.drawCenteredString(this.font, sub, x + cellW / 2, y + 60, FdpWidgets.TEXT_DIM);
        }

        graphics.drawCenteredString(this.font, "第 " + (page + 1) + " / " + totalPages + " 页",
                left + w / 2, top + PANEL_H - 14, FdpWidgets.TEXT_DIM);
        graphics.drawString(this.font, "右键两次删除网络条目",
                left + 10, top + PANEL_H - 14, FdpWidgets.TEXT_DIM);
    }

    /**
     * 机械动力风格的黄铜窗口边框。
     *
     * 与 Create 官方 ValueSettingsScreen.renderBrassFrame 逐参数一致（javap 字节码核对）：
     * 四角 4×4 角件 + 左右 3px 竖条 drawStretched 拉伸 + 上下 3px 横条 drawCropped。
     * 贴图为 Create 的 value_settings.png（packed 段 BRASS_FRAME_*）。
     *
     * 注意：drawCropped 的采样宽度跟随目标宽度（maxU = startX + w），
     * 我们的窗口横条（w-8=352）超过贴图段宽 256，必须拆成两段各 ≤248，否则 UV 越界。
     */
    private void renderBrassFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        AllGuiTextures.BRASS_FRAME_TL.render(graphics, x, y);
        AllGuiTextures.BRASS_FRAME_TR.render(graphics, x + w - 4, y);
        AllGuiTextures.BRASS_FRAME_BL.render(graphics, x, y + h - 4);
        AllGuiTextures.BRASS_FRAME_BR.render(graphics, x + w - 4, y + h - 4);
        if (h > 8) {
            UIRenderHelper.drawStretched(graphics, x, y + 4, 3, h - 8, 0,
                    AllGuiTextures.BRASS_FRAME_LEFT);
            UIRenderHelper.drawStretched(graphics, x + w - 3, y + 4, 3, h - 8, 0,
                    AllGuiTextures.BRASS_FRAME_RIGHT);
        }
        if (w > 8) {
            int edge = w - 8;
            int half = edge / 2;
            UIRenderHelper.drawCropped(graphics, x + 4, y, half, 3, 0,
                    AllGuiTextures.BRASS_FRAME_TOP);
            UIRenderHelper.drawCropped(graphics, x + 4 + half, y, edge - half, 3, 0,
                    AllGuiTextures.BRASS_FRAME_TOP);
            UIRenderHelper.drawCropped(graphics, x + 4, y + h - 3, half, 3, 0,
                    AllGuiTextures.BRASS_FRAME_BOTTOM);
            UIRenderHelper.drawCropped(graphics, x + 4 + half, y + h - 3, edge - half, 3, 0,
                    AllGuiTextures.BRASS_FRAME_BOTTOM);
        }
    }

    private String getExt(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : "";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 右键：删除网络条目（两步确认，防误触；本地文件不受列表管理）
        if (button == 1) {
            Grid g = grid();
            int start = page * perPage;
            for (int i = 0; i < perPage; i++) {
                int idx = start + i;
                if (idx >= entries.size()) break;
                if (g.hit(i, mouseX, mouseY)) {
                    Entry e = entries.get(idx);
                    if (!e.isNet()) {
                        status = "本地文件请在 flap-media 目录自行删除";
                        pendingDeleteKey = null;
                        return true;
                    }
                    String key = e.key();
                    if (key.equals(pendingDeleteKey)
                            && System.currentTimeMillis() - pendingDeleteAt < 8000) {
                        deleteNetEntry(key, e.name());
                    } else {
                        // 第一步：标记待删（8 秒内再右键同一格才真正删除）
                        pendingDeleteKey = key;
                        pendingDeleteAt = System.currentTimeMillis();
                        status = "⚠ 再右键一次确认删除：" + e.name() + "（左键取消）";
                    }
                    return true;
                }
            }
        }
        if (button == 0) {
            pendingDeleteKey = null; // 左键任意操作取消待删状态
            // 命中判定与绘制共用 grid()：几何只有一个来源，不会再出现「点到的和看到的错位」
            Grid g = grid();
            int start = page * perPage;
            for (int i = 0; i < perPage; i++) {
                int idx = start + i;
                if (idx >= entries.size()) break;
                if (g.hit(i, mouseX, mouseY)) {
                    Entry e = entries.get(idx);
                    // 网页条目：再次点击已选中的网页 → 打开全屏预览（可点击/打字/滚动）
                    if (WebScreenManager.isWebPath(e.key()) && e.key().equals(selectedPath)) {
                        WebScreenManager.openPreview(e.key());
                        return true;
                    }
                    selectedPath = e.key();
                    status = "已选择：" + e.name()
                            + (WebScreenManager.isWebPath(e.key()) ? "（再点一次可打开预览交互）" : "");
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 该布谷鸟时钟当前【已应用】的媒体路径（读方块实体上的同步数据）。
     *
     * 删除条目时判断「要不要顺手清空翻牌」必须用这个，而不是 GUI 里的 selectedPath：
     * selectedPath 只是玩家在列表里**点选**的条目（点击即变），跟时钟实际显示的内容
     * 常常不一致。曾经用 selectedPath 判断，导致「删掉一个没在显示的条目，
     * 却把时钟正在显示的那个网页一起清掉（浏览器被关 → 页面状态全丢）」。
     */
    private String appliedMediaPath() {
        try {
            if (Minecraft.getInstance().level == null) {
                return "";
            }
            BlockEntity be = Minecraft.getInstance().level.getBlockEntity(cuckooPos);
            if (be instanceof CuckooClockMedia cuckoo) {
                String p = cuckoo.flapdisplayplus$getMediaPath();
                return p == null ? "" : p;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 真正执行删除（两步确认后调用）：从列表与 net-list.txt 移除，
     * 关掉对应浏览器/声音；**仅当该时钟当前确实在显示这个条目时**，
     * 同步发包清空服务端媒体（否则翻牌会懒重建浏览器、继续显示已删网页）。
     */
    private void deleteNetEntry(String key, String name) {
        netItems.remove(key);
        saveNetItems();
        rebuildEntries();
        pendingDeleteKey = null;
        if (WebScreenManager.isWebPath(key)) {
            WebScreenManager.stop(key); // 连浏览器和声音一起关掉
        }
        String extra;
        if (key.equals(appliedMediaPath())) {
            // 该时钟当前正显示它 → 发包清空，翻牌立即恢复原版显示
            if (key.equals(selectedPath)) {
                selectedPath = "";
                sourceType = CuckooClockMedia.SOURCE_IMAGE;
            }
            ModNetwork.CHANNEL.sendToServer(new SetMediaPacket(
                    cuckooPos, "", displayMode, CuckooClockMedia.SOURCE_IMAGE));
            extra = "，翻牌上的它已同步清空";
        } else {
            extra = "（该时钟当前显示的不是它，未动翻牌）";
        }
        status = "已删除：" + name + extra;
    }
}
