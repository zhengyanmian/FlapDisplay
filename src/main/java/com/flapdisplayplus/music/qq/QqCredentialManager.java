/*
 * QqCredentialManager.java
 *
 * QQ 音乐登录凭证的本地持久化管理（移植自 NetMusicCanNeedQQ，原作者 Yincmewy，BSD-3-Clause）。
 * 凭证以 JSON 形式保存在游戏配置目录下的 netmusicdisplay/qq_credential.json。
 */
package com.flapdisplayplus.music.qq;

import com.flapdisplayplus.music.MusicNetIntegration;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.flapdisplayplus.FlapDisplayPlus;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QqCredentialManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile QqCredential credential;
    private static volatile Path credentialFile;

    private QqCredentialManager() {
    }

    /** 在客户端启动早期调用，指定配置目录 */
    public static void init(Path configDir) {
        Path dir = configDir.resolve("netmusicdisplay");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MusicNetIntegration.LOGGER.error("[QQ登录] 创建配置目录失败", e);
        }
        credentialFile = dir.resolve("qq_credential.json");
        load();
    }

    public static void load() {
        if (credentialFile == null || !Files.exists(credentialFile)) {
            credential = null;
            return;
        }
        try (Reader reader = Files.newBufferedReader(credentialFile, StandardCharsets.UTF_8)) {
            credential = GSON.fromJson(reader, QqCredential.class);
        } catch (Exception e) {
            MusicNetIntegration.LOGGER.error("[QQ登录] 读取凭证失败", e);
            credential = null;
        }
    }

    public static void save(QqCredential cred) {
        credential = cred;
        if (credentialFile == null) {
            return;
        }
        try {
            Files.createDirectories(credentialFile.getParent());
            try (Writer writer = Files.newBufferedWriter(credentialFile, StandardCharsets.UTF_8)) {
                GSON.toJson(cred, writer);
            }
        } catch (IOException e) {
            MusicNetIntegration.LOGGER.error("[QQ登录] 保存凭证失败", e);
        }
    }

    public static void clear() {
        credential = null;
        if (credentialFile != null && Files.exists(credentialFile)) {
            try {
                Files.delete(credentialFile);
            } catch (IOException e) {
                MusicNetIntegration.LOGGER.error("[QQ登录] 删除凭证失败", e);
            }
        }
    }

    public static QqCredential getCredential() {
        return credential;
    }

    public static boolean hasValidCredential() {
        QqCredential cred = credential;
        return cred != null && cred.isValid() && !cred.isExpired();
    }

    public static String getEffectiveCookie() {
        QqCredential cred = credential;
        if (cred != null && cred.isValid() && !cred.isExpired()) {
            return cred.toCookieString();
        }
        return "";
    }

    public static String getMusicId() {
        QqCredential cred = credential;
        return cred != null ? cred.getMusicId() : "";
    }
}
