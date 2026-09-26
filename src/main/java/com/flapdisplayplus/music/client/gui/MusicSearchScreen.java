package com.flapdisplayplus.music.client.gui;

import com.flapdisplayplus.music.config.Config;
import com.flapdisplayplus.music.search.ActivePlatform;
import com.flapdisplayplus.music.search.IMusicSearchSource;
import com.flapdisplayplus.music.search.SearchResult;
import com.flapdisplayplus.music.search.SearchSourceManager;
import com.flapdisplayplus.music.search.qqmusic.QQMusicSearchSource;
import com.flapdisplayplus.music.search.qqmusic.QqSearchCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class MusicSearchScreen extends Screen implements SearchResultHost {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    /**
     * 多平台歌曲搜索界面（客户端）。
     *
     * 设计：选中歌曲后把平台对应的标识回填到刻录机输入框（筐），由用户点击
     * 「制作唱片」完成刻录：
     * - 网易云：music.163.com/song?id=XXX（原版刻录机可直接识别）
     * - QQ音乐：qqmusic:{mid}（刻录机拦截制作，按 mid 写入 CD）
     *
     * 结果列表支持两种模式（在配置界面选择）：
     * - 滚动：列出全部结果，鼠标滚轮上下滚动
     * - 翻页：每页 N 首，上一页/下一页切换
     */
    private final Screen host;
    private IMusicSearchSource currentSource;

    private EditBox searchBox;
    private Button searchButton;
    private Button prevButton;
    private Button nextButton;

    private List<SearchResult> results = List.of();
    private String status = "";
    private boolean searching = false;

    // 滚动模式
    private int scrollTop = 0;
    private final List<ItemWidget> itemWidgets = new ArrayList<>();
    // 翻页模式
    private int page = 0;

    private int resultAreaTop = 64;
    private int resultAreaBottom = 0;
    private int rowHeight = 22;

    public MusicSearchScreen(Screen host) {
        super(Component.literal("搜索歌曲"));
        this.host = host;
        // 使用刻录机界面上选择的平台（网易云 / QQ音乐）
        this.currentSource = SearchSourceManager.get(ActivePlatform.ID);
    }

    @Override
    protected void init() {
        this.itemWidgets.clear();
        this.resultAreaTop = 64;
        this.resultAreaBottom = this.height - 36;
        this.rowHeight = 22;

        int cx = this.width / 2;
        int panelLeft = Math.max(10, cx - 200);
        int panelWidth = Math.min(this.width - 20, 400);

        // 第1行：[========搜索框========] [搜索]（平台由刻录机界面里的切换按钮决定）
        int boxX = panelLeft;
        int boxW = panelWidth - 54;
        this.searchBox = new EditBox(this.font, boxX, 36, Math.max(boxW, 80), 20,
                Component.literal("输入歌名/歌手"));
        this.searchBox.setMaxLength(64);
        this.addRenderableWidget(this.searchBox);

        this.searchButton = Button.builder(Component.literal("搜索"), btn -> doSearch())
                .bounds(boxX + boxW + 4, 36, 46, 20)
                .build();
        this.addRenderableWidget(this.searchButton);

        // 翻页按钮（仅翻页模式可见），放在状态栏上方
        this.prevButton = Button.builder(Component.literal("上一页"), btn -> prevPage())
                .bounds(this.width / 2 - 104, this.height - 22, 100, 18)
                .build();
        this.nextButton = Button.builder(Component.literal("下一页"), btn -> nextPage())
                .bounds(this.width / 2 + 4, this.height - 22, 100, 18)
                .build();
        this.addRenderableWidget(this.prevButton);
        this.addRenderableWidget(this.nextButton);

        this.setInitialFocus(this.searchBox);

        if (!this.results.isEmpty()) {
            rebuildItems();
        }
    }

    private void doSearch() {
        String keyword = this.searchBox.getValue().trim();
        if (keyword.isEmpty()) {
            this.status = "请输入歌名或歌手";
            return;
        }
        // QQ 音乐搜索功能开发中
        if (this.currentSource instanceof QQMusicSearchSource) {
            this.status = "QQ音乐搜索功能开发中，请切换至网易云";
            return;
        }
        this.searching = true;
        this.status = "搜索中...";
        clearResults();

        IMusicSearchSource source = this.currentSource;
        String query = keyword;
        // 拉取足够多的结果用于滚动/翻页
        source.search(query, 30).whenComplete((list, ex) ->
                Minecraft.getInstance().execute(() -> {
                    this.searching = false;
                    if (ex != null) {
                        LOGGER.error("搜索异常", ex);
                        this.status = "搜索失败：" + (ex.getMessage() == null ? "网络错误" : ex.getMessage());
                        return;
                    }
                    if (list == null || list.isEmpty()) {
                        this.status = "无结果，换个关键词试试";
                        return;
                    }
                    this.results = list;
                    this.page = 0;
                    this.scrollTop = 0;
                    this.status = "找到 " + list.size() + " 首歌曲";
                    rebuildItems();
                })
        );
    }

    /** 根据当前模式与数据重建可见的歌曲条目控件 */
    private void rebuildItems() {
        for (ItemWidget w : this.itemWidgets) {
            this.removeWidget(w.button);
        }
        this.itemWidgets.clear();

        boolean paginate = Config.SEARCH_LIST_MODE.get() == Config.SearchListMode.PAGINATE;
        // 翻页模式：底部留给翻页按钮 + 页号
        this.resultAreaBottom = paginate ? this.height - 48 : this.height - 36;
        if (this.prevButton != null) this.prevButton.visible = paginate && !this.results.isEmpty();
        if (this.nextButton != null) this.nextButton.visible = paginate && !this.results.isEmpty();

        if (this.results.isEmpty()) {
            return;
        }

        Config.SearchListMode mode = Config.SEARCH_LIST_MODE.get();
        int perPage = Config.SEARCH_PAGE_SIZE.get();
        List<SearchResult> visible;
        if (mode == Config.SearchListMode.PAGINATE) {
            int from = Math.min(this.page * perPage, this.results.size());
            int to = Math.min(from + perPage, this.results.size());
            visible = this.results.subList(from, to);
        } else {
            int from = this.scrollTop;
            int maxVisible = Math.max(1, (this.resultAreaBottom - this.resultAreaTop) / this.rowHeight);
            int to = Math.min(from + maxVisible, this.results.size());
            visible = this.results.subList(from, to);
        }

        int cx = this.width / 2;
        int btnW = Math.min(380, this.width - 30);
        int btnX = (this.width - btnW) / 2;
        int y = this.resultAreaTop;
        for (SearchResult r : visible) {
            String label = r.displayName() + "  (" + r.durationText() + ")";
            if (label.length() > 46) label = label.substring(0, 44) + "..";
            final SearchResult fr = r;
            Button b = Button.builder(Component.literal(label), btn -> selectResult(fr))
                    .bounds(btnX, y, btnW, 20)
                    .build();
            this.addRenderableWidget(b);
            this.itemWidgets.add(new ItemWidget(b, fr));
            y += this.rowHeight;
        }
    }

    private void clearResults() {
        for (ItemWidget w : this.itemWidgets) {
            this.removeWidget(w.button);
        }
        this.itemWidgets.clear();
        this.results = List.of();
    }

    /** 选中歌曲：把标识回填到刻录机输入框，并提示用户点击「制作唱片」 */
    private void selectResult(SearchResult r) {
        // QQ 结果暂存，刻录机拦截制作时直接取歌名/时长
        if (QQMusicSearchSource.URL_PREFIX.equals(r.platform()) || r.platform().equals("qqmusic")) {
            QqSearchCache.put(r);
        }
        if (this.host instanceof SearchResultHost) {
            ((SearchResultHost) this.host).netmusicdisplay$applySearchResult(r.cdUrl());
            this.status = "已填入刻录机，请点击「制作唱片」";
            this.onClose();
        } else {
            this.status = "回填失败：宿主界面不支持";
        }
    }

    @Override
    public void netmusicdisplay$applySearchResult(String value) {
        // 不应在此被调用（本界面是发起方）
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
            this.doSearch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (Config.SEARCH_LIST_MODE.get() == Config.SearchListMode.SCROLL
                && !this.results.isEmpty()) {
            int maxVisible = Math.max(1, (this.resultAreaBottom - this.resultAreaTop) / this.rowHeight);
            int maxTop = Math.max(0, this.results.size() - maxVisible);
            this.scrollTop = Math.max(0, Math.min(maxTop, this.scrollTop - (int) Math.signum(scrollY)));
            rebuildItems();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        // 翻页控件
        if (Config.SEARCH_LIST_MODE.get() == Config.SearchListMode.PAGINATE && !this.results.isEmpty()) {
            int perPage = Config.SEARCH_PAGE_SIZE.get();
            int totalPages = Math.max(1, (this.results.size() + perPage - 1) / perPage);
            int cx = this.width / 2;
            String pageText = "第 " + (this.page + 1) + " / " + totalPages + " 页";
            graphics.drawCenteredString(this.font, pageText, cx, this.resultAreaBottom + 6, 0xCCCCCC);
        }

        // 状态栏
        if (!this.status.isEmpty()) {
            int color = this.status.startsWith("找到") || this.status.startsWith("已填入")
                    ? 0x55FF55 : 0xFFAA00;
            if (this.status.contains("失败") || this.status.contains("错误")) color = 0xFF5555;
            int statusY = Config.SEARCH_LIST_MODE.get() == Config.SearchListMode.PAGINATE
                    ? this.height - 66 : this.height - 22;
            graphics.drawCenteredString(this.font, this.status, this.width / 2, statusY, color);
        }

        if (this.results.isEmpty() && !this.searching && this.status.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "输入歌名后回车搜索，点击结果填入刻录机",
                    this.width / 2, this.height - 36, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.host);
        } else {
            super.onClose();
        }
    }

    /** 翻页控制：上一页/下一页 */
    public void prevPage() {
        if (this.page > 0) {
            this.page--;
            rebuildItems();
        }
    }

    public void nextPage() {
        int perPage = Config.SEARCH_PAGE_SIZE.get();
        int totalPages = Math.max(1, (this.results.size() + perPage - 1) / perPage);
        if (this.page < totalPages - 1) {
            this.page++;
            rebuildItems();
        }
    }

    /** 翻页按钮需要在界面初始化时添加（外部不调用，留给后续扩展） */

    private static final class ItemWidget {
        final Button button;
        final SearchResult result;

        ItemWidget(Button button, SearchResult result) {
            this.button = button;
            this.result = result;
        }
    }
}
