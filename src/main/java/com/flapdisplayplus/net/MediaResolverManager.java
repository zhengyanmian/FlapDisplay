/*
 * MediaResolverManager.java
 *
 * 解析器调度：按优先级依次尝试各解析器。
 *
 * 现只保留直链解析（无需外部工具、零依赖）：图床/CDN/视频直链可直接播放。
 * 站点解析功能已按需求移除——只支持 http(s) 直链。
 *
 * 所有解析都应在【后台线程】调用，不要阻塞渲染线程。
 */
package com.flapdisplayplus.net;

import com.flapdisplayplus.FlapDisplayPlus;

import java.util.List;
import java.util.Locale;

public final class MediaResolverManager {

    private MediaResolverManager() {
    }

    private static final List<MediaResolver> RESOLVERS = List.of(
            new DirectLinkResolver()
    );

    /** 是否是网络链接（本地文件路径返回 false） */
    public static boolean isNetworkInput(String input) {
        if (input == null) {
            return false;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * 解析链接。应在后台线程调用。
     *
     * @return 解析结果；不会返回 null
     * @throws Exception 全部解析器都失败时，抛出最后一个解析器的错误（消息可直接展示给用户）
     */
    public static ResolvedMedia resolve(String input) throws Exception {
        if (!isNetworkInput(input)) {
            throw new IllegalArgumentException("不是网络链接：" + input);
        }
        Exception last = null;
        for (MediaResolver r : RESOLVERS) {
            if (!r.canHandle(input)) {
                continue;
            }
            try {
                ResolvedMedia rm = r.resolve(input);
                if (rm != null && rm.directUrl != null && !rm.directUrl.isEmpty()) {
                    FlapDisplayPlus.LOGGER.info("[Resolver] {} 解析成功: {}", r.name(), rm);
                    return rm;
                }
            } catch (Exception e) {
                last = e;
                FlapDisplayPlus.LOGGER.debug("[Resolver] {} 解析失败: {}", r.name(), e.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("没有可用于该链接的解析器。");
    }
}
