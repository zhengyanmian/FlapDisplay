/*
 * MediaResolver.java
 *
 * 网络媒体解析器接口。
 *
 * 合规边界（重要）：
 * 本模组【不包含】任何针对具体视频网站的签名计算、防盗链参数构造、cookie 伪造
 * 或付费内容绕过逻辑。需要解析站点页面链接时，一律通过 YtDlpResolver 以子进程
 * 方式调用用户本机自行安装的 yt-dlp，站点适配由 yt-dlp 上游维护。
 * 这样既避免法律风险，也避免几周一变的解析规则成为本项目的维护负担。
 */
package com.flapdisplayplus.net;

public interface MediaResolver {

    /** 该解析器能否处理这个输入（链接或本地路径） */
    boolean canHandle(String input);

    /**
     * 解析为可下载的直链。
     *
     * @throws Exception 解析失败（工具缺失、超时、站点不支持等），消息可直接展示给用户
     */
    ResolvedMedia resolve(String input) throws Exception;

    /** 解析器名称，用于日志与错误提示 */
    String name();
}
