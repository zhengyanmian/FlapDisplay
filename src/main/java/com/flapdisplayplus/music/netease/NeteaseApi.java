package com.flapdisplayplus.music.netease;

import com.flapdisplayplus.music.compat.NetWorkerBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云登录 API（明文 GET，无需 weapi 加密）。
 * 供登录 GUI 和指令共同调用。
 */
public class NeteaseApi {

    private static final Map<String, String> HEADERS = new HashMap<>() {{
        put("Content-Type", "application/x-www-form-urlencoded");
        put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.6");
        put("Referer", "https://music.163.com");
    }};

    /** 获取二维码 key */
    public static String getQRKey() throws Exception {
        String body = NetWorkerBridge.get("https://music.163.com/api/login/qrcode/unikey?type=3", HEADERS);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        return obj.has("unikey") ? obj.get("unikey").getAsString() : null;
    }

    /** 检查扫码状态，返回完整响应（含 headers） */
    public static HttpResponse<String> checkQRStatus(String key) throws Exception {
        return httpGet("https://music.163.com/api/login/qrcode/client/login?key=" + key + "&type=3");
    }

    /** 邮箱登录（密码 MD5） */
    public static HttpResponse<String> emailLogin(String email, String password) throws Exception {
        String url = String.format(
                "https://music.163.com/api/w/login/?username=%s&rememberLogin=true&password=%s&https=true&type=0",
                urlEncode(email), md5(password));
        return httpGet(url);
    }

    /** 发送手机验证码 */
    public static String sendCaptcha(String phone) throws Exception {
        return NetWorkerBridge.get(
                String.format("https://music.163.com/api/sms/captcha/sent?cellphone=%s&ctcode=86", phone),
                HEADERS);
    }

    /** 手机验证码登录 */
    public static HttpResponse<String> phoneCaptchaLogin(String phone, String captcha) throws Exception {
        String url = String.format(
                "https://music.163.com/api/w/login/cellphone?phone=%s&countrycode=86&rememberLogin=true&captcha=%s&https=true&type=1",
                phone, urlEncode(captcha));
        return httpGet(url);
    }

    /**
     * 获取歌曲播放信息（含 VIP 音质）。
     * level 音质等级：standard / higher / exhigh / lossless / hires
     * 返回 JSON：{ data: [{ code:200, url:"...", time:180000 }] }
     */
    public static String getPlayInfo(long musicId, String level) throws Exception {
        String url = String.format(
                "https://music.163.com/api/song/enhance/player/url/v1?encodeType=flac&ids=[%d]&level=%s",
                musicId, level);
        Map<String, String> headers = new HashMap<>(HEADERS);
        // 带 Cookie 才能获取 VIP 音质
        String cookie = com.flapdisplayplus.music.config.Config.NETEASE_COOKIE.get();
        if (cookie != null && !cookie.trim().isEmpty()) {
            headers.put("Cookie", cookie.trim());
        }
        return NetWorkerBridge.get(url, headers);
    }

    /** 从响应头 Set-Cookie 提取 Cookie，组合成 "key=value; key=value" 字符串 */
    public static String extractCookie(HttpResponse<String> resp) {
        List<String> setCookies = resp.headers().allValues("Set-Cookie");
        if (setCookies == null || setCookies.isEmpty()) return null;
        Map<String, String> cookies = new HashMap<>();
        for (String sc : setCookies) {
            String body = sc.split(";")[0].trim();
            String[] parts = body.split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        }
        if (cookies.isEmpty()) return null;
        // 追加 Better Login 也加的固定参数
        cookies.putIfAbsent("os", "pc");
        cookies.putIfAbsent("appver", "3.1.6");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    /** 解析响应 JSON 里的 code 字段 */
    public static int parseCode(HttpResponse<String> resp) {
        try {
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            return obj.has("code") ? obj.get("code").getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static HttpResponse<String> httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", HEADERS.get("User-Agent"))
                .header("Referer", HEADERS.get("Referer"))
                .GET()
                .build();
        return NetWorkerBridge.send(request);
    }

    public static String md5(String str) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] array = md.digest(str.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : array) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
