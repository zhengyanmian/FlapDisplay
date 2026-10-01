package com.flapdisplayplus.music;

import com.github.tartaricacid.netmusic.api.NetWorker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 统一的 HTTP 工具。
 *
 * 关键：所有对音乐平台 API 的请求都通过 Net Music 提供的 {@link NetWorker#getProxyFromConfig()}
 * 获取代理设置，而不是直接 new 一个无代理的 HttpClient。这是客户端能正常联网的前提
 * （用户的 Net Music 可能配置了代理，或官方接口要求特定出口）。若该静态方法不可用，
 * 则回退到直连（Proxy.NO_PROXY），不阻断流程。
 */
public final class HttpUtil {

    private HttpUtil() {
    }

    private static Proxy proxy = null;
    private static boolean proxyResolved = false;

    private static Proxy getProxy() {
        if (!proxyResolved) {
            proxyResolved = true;
            try {
                proxy = NetWorker.getProxyFromConfig();
            } catch (Throwable t) {
                proxy = Proxy.NO_PROXY;
            }
        }
        return proxy == null ? Proxy.NO_PROXY : proxy;
    }

    public static String postJson(String url, String body, Map<String, String> headers) throws IOException {
        URL u = new URL(url);
        java.net.URLConnection conn = u.openConnection(getProxy());
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setDoInput(true);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (headers == null || !headers.containsKey("Content-Type")) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public static String get(String url, Map<String, String> headers) throws IOException {
        URL u = new URL(url);
        java.net.URLConnection conn = u.openConnection(getProxy());
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoInput(true);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
