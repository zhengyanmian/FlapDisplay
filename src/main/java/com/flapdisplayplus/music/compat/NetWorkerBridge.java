/*
 * NetWorkerBridge.java
 *
 * Net Music 的 NetWorker 反射桥。
 *
 * 【为什么存在】NetWorker 是网络音乐机(Net Music)提供的 HTTP 工具（代理/出口配置）。
 * HttpUtil 与 NeteaseApi 过去直接 import 它——Net Music 未安装时，这两个类的
 * 字节码校验需要解析 NetWorker 类型（返回值赋值兼容性检查）→ NoClassDefFoundError
 * （是 Error，catch(Exception) 接不住），软联动名存实亡。
 *
 * 本类【绝不 import 任何 netmusic 类】：全部通过 Class.forName(字符串) 懒解析。
 * Net Music 未安装时各方法回退到无代理直连，流程不中断。
 *
 * NetWorker 真实签名（javap 在 flap-display-plus-1.20.1/libs/netmusic.jar 上验证）：
 *   public static String get(String, Map<String,String>) throws IOException;
 *   public static Proxy getProxyFromConfig();
 *   public static <T> HttpResponse<T> send(HttpRequest, HttpResponse.BodyHandler<T>) throws IOException;
 */
package com.flapdisplayplus.music.compat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public final class NetWorkerBridge {

    private static boolean resolved = false;
    private static Method mGet;
    private static Method mGetProxy;
    private static Method mSend;

    private NetWorkerBridge() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> nw = Class.forName("com.github.tartaricacid.netmusic.api.NetWorker",
                    false, NetWorkerBridge.class.getClassLoader());
            mGet = nw.getMethod("get", String.class, Map.class);
            mGetProxy = nw.getMethod("getProxyFromConfig");
            mSend = nw.getMethod("send", HttpRequest.class, HttpResponse.BodyHandler.class);
        } catch (Throwable t) {
            mGet = null;
            mGetProxy = null;
            mSend = null;
        }
    }

    /** NetWorker.getProxyFromConfig()；不可用时回退直连 */
    public static Proxy getProxyFromConfig() {
        resolve();
        try {
            if (mGetProxy != null) {
                return (Proxy) mGetProxy.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return Proxy.NO_PROXY;
    }

    /** NetWorker.get(url, headers)；不可用时抛 IOException（调用方自行兜底） */
    public static String get(String url, Map<String, String> headers) throws IOException {
        resolve();
        if (mGet == null) {
            throw new IOException("Net Music (NetWorker) is not installed");
        }
        try {
            return (String) mGet.invoke(null, url, headers);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException("NetWorker.get failed", c);
        } catch (Throwable t) {
            throw new IOException("NetWorker.get failed", t);
        }
    }

    /** NetWorker.send(request, BodyHandlers.ofString())；不可用时抛 IOException */
    public static HttpResponse<String> send(HttpRequest request) throws IOException {
        resolve();
        if (mSend == null) {
            throw new IOException("Net Music (NetWorker) is not installed");
        }
        try {
            @SuppressWarnings("unchecked")
            HttpResponse<String> resp = (HttpResponse<String>) mSend.invoke(null,
                    request, HttpResponse.BodyHandlers.ofString());
            return resp;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException("NetWorker.send failed", c);
        } catch (Throwable t) {
            throw new IOException("NetWorker.send failed", t);
        }
    }
}
