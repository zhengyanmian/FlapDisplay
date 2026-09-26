package com.flapdisplayplus.music.search;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 音乐搜索源抽象（客户端）。
 *
 * 每个支持的平台实现一个搜索源：
 * - 网易云：复用 Net Music 自带的 NeteaseMusicSearch
 * - QQ 音乐：c.y.qq.com 公开接口
 * - 酷狗 / 酷狗概念版：kg 接口
 *
 * 搜索在独立线程执行（返回 CompletableFuture），不阻塞渲染线程。
 */
public interface IMusicSearchSource {

    /** 平台唯一 ID，用于持久化与歌词分发 */
    String getPlatformId();

    /** 界面显示名，如「QQ音乐」「酷狗音乐」 */
    String getDisplayName();

    /**
     * 搜索歌曲。
     * @param keyword 关键词
     * @param limit   最多返回条数
     * @return 异步搜索结果；失败时返回空列表（不抛异常）
     */
    CompletableFuture<List<SearchResult>> search(String keyword, int limit);

    /** 是否已登录（未实现登录的平台始终返回 true，表示免登录可用） */
    default boolean isLoggedIn() {
        return true;
    }
}
