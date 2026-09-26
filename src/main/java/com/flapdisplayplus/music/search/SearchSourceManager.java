package com.flapdisplayplus.music.search;

import com.flapdisplayplus.music.search.netease.NetEaseSearchSource;
import com.flapdisplayplus.music.search.qqmusic.QQMusicSearchSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索源注册表（客户端）。
 *
 * 注册顺序即界面切换顺序。第一个为默认搜索源。
 */
public final class SearchSourceManager {

    private static final List<IMusicSearchSource> SOURCES = new ArrayList<>();
    private static int defaultIndex = 0;

    private SearchSourceManager() {
    }

    /** 注册所有搜索源（客户端启动时调用一次） */
    public static void registerAll() {
        if (!SOURCES.isEmpty()) {
            return;
        }
        register(new NetEaseSearchSource());
        register(new QQMusicSearchSource());
        // 酷狗 / 酷狗概念版后续接入
    }

    public static void register(IMusicSearchSource source) {
        SOURCES.add(source);
    }

    /** 全部搜索源（不可变视图） */
    public static List<IMusicSearchSource> getSources() {
        return List.copyOf(SOURCES);
    }

    /** 默认搜索源（网易云） */
    public static IMusicSearchSource getDefault() {
        if (SOURCES.isEmpty()) {
            registerAll();
        }
        return SOURCES.get(defaultIndex);
    }

    /** 按平台 ID 查找 */
    public static IMusicSearchSource get(String platformId) {
        if (SOURCES.isEmpty()) {
            registerAll();
        }
        for (IMusicSearchSource source : SOURCES) {
            if (source.getPlatformId().equals(platformId)) {
                return source;
            }
        }
        return getDefault();
    }

    /** 界面循环切换：返回下一个源 */
    public static IMusicSearchSource next(IMusicSearchSource current) {
        if (SOURCES.isEmpty()) {
            registerAll();
        }
        int idx = SOURCES.indexOf(current);
        if (idx < 0) {
            return getDefault();
        }
        return SOURCES.get((idx + 1) % SOURCES.size());
    }
}
