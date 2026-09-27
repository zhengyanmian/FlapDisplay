/*
 * MediaResolver.java
 *
 * 网络媒体解析器接口。
 *
 * 合规边界（重要）：
 * 本模组【不包含】任何针对具体视频网站的签名计算、防盗链参数构造、cookie 伪造
 * 或付费内容绕过逻辑，也不做站点页面解析——只支持 http(s) 直链下载。
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
