/*
 * NetMusicCompat.java
 *
 * 将解析好的歌曲信息通过反射写入 Net Music 的 CD 刻录结果。
 *
 * 移植自开源项目 NetMusicCanNeedQQ (https://github.com/Yincmewy/NetMusicCanNeedQQ)
 * 原作者：Yincmewy。原始协议：BSD-3-Clause。
 *
 * BSD-3-Clause 版权声明：
 * Copyright (c) Yincmewy. All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the above copyright notice,
 * this list of conditions and the following disclaimer are met.
 * THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 *
 * 说明：Net Music 的 ItemMusicCD$SongInfo 与 SetMusicIDMessage 是闭源内部类，
 * 无法通过正常 API 构造，因此采用反射桥接（与上游实现一致）。
 *
 * 注意：Net Music 1.5.1 已切换为 NeoForge 新网络 API，发包方式是
 *   NetworkHandler.sendToServer(CustomPacketPayload)  // 静态方法
 * 旧版本的 NetworkHandler.CHANNEL 字段已不存在，故此处直接反射调用该静态方法。
 */
package com.flapdisplayplus.music.compat;

import com.flapdisplayplus.music.MusicNetIntegration;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.data.SongInfoData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class NetMusicCompat {
    private static final String SONG_INFO_CLASS = "com.github.tartaricacid.netmusic.item.ItemMusicCD$SongInfo";
    private static final String SET_MESSAGE_CLASS = "com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage";
    private static final String NETWORK_HANDLER_CLASS = "com.github.tartaricacid.netmusic.network.NetworkHandler";

    private static boolean initialized;
    private static boolean initFailed;
    private static Constructor<?> songInfoCtor;
    private static Constructor<?> setMessageCtor;
    private static Method sendToServerMethod;
    private static Field transNameField;
    private static Field vipField;
    private static Field readOnlyField;
    private static Field artistsField;

    private NetMusicCompat() {
    }

    /** 把一首歌的解析结果发给服务器，写入当前刻录的 CD。返回是否成功。 */
    public static boolean sendSongToServer(SongInfoData info) {
        if (info == null) {
            MusicNetIntegration.LOGGER.error("[NetMusicCompat] 写入失败：SongInfoData 为 null");
            return false;
        }
        if (!info.isValid()) {
            MusicNetIntegration.LOGGER.error("[NetMusicCompat] 写入失败：数据不合法 songUrl={} songName={} songTime={}",
                    info.songUrl, info.songName, info.songTime);
            return false;
        }
        if (!init()) {
            MusicNetIntegration.LOGGER.error("[NetMusicCompat] 写入失败：反射初始化未通过（Net Music 类缺失或版本不匹配）");
            return false;
        }
        try {
            Object songInfo = songInfoCtor.newInstance(info.songUrl, info.songName, info.songTime, info.readOnly);
            applyOptionalFields(songInfo, info);
            Object message = setMessageCtor.newInstance(songInfo);
            // NetworkHandler.sendToServer 是静态方法，传 null 作为实例
            sendToServerMethod.invoke(null, message);
            MusicNetIntegration.LOGGER.info("[NetMusicCompat] 已发送歌曲信息到服务器：{}", info.songName);
            return true;
        } catch (ReflectiveOperationException e) {
            MusicNetIntegration.LOGGER.error("[NetMusicCompat] 发送歌曲信息失败", e);
            return false;
        }
    }

    private static synchronized boolean init() {
        if (initialized) {
            return !initFailed;
        }
        initialized = true;
        try {
            Class<?> songInfoClass = Class.forName(SONG_INFO_CLASS);
            songInfoCtor = songInfoClass.getConstructor(String.class, String.class, int.class, boolean.class);
            setMessageCtor = Class.forName(SET_MESSAGE_CLASS).getConstructor(songInfoClass);
            Class<?> networkHandlerClass = Class.forName(NETWORK_HANDLER_CLASS);
            // 不再硬编码 CustomPacketPayload 类名（NeoForge 不同版本路径可能变化），
            // 改为遍历找到名为 sendToServer 且只接受 1 个参数的静态方法。
            sendToServerMethod = null;
            for (Method m : networkHandlerClass.getMethods()) {
                if ("sendToServer".equals(m.getName())
                        && m.getParameterCount() == 1
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    sendToServerMethod = m;
                    break;
                }
            }
            if (sendToServerMethod == null) {
                throw new NoSuchMethodException("NetworkHandler.sendToServer(1 param) 未找到");
            }

            transNameField = songInfoClass.getField("transName");
            vipField = songInfoClass.getField("vip");
            readOnlyField = songInfoClass.getField("readOnly");
            artistsField = songInfoClass.getField("artists");
        } catch (ReflectiveOperationException e) {
            initFailed = true;
            MusicNetIntegration.LOGGER.error("[NetMusicCompat] 未找到 Net Music 类，写CD功能已禁用", e);
        }
        return !initFailed;
    }

    private static void applyOptionalFields(Object songInfo, SongInfoData info) throws IllegalAccessException {
        if (transNameField != null && info.transName != null && !info.transName.isBlank()) {
            transNameField.set(songInfo, info.transName);
        }
        if (vipField != null) {
            vipField.setBoolean(songInfo, info.vip);
        }
        if (readOnlyField != null) {
            readOnlyField.setBoolean(songInfo, info.readOnly);
        }
        if (artistsField != null && info.artists != null && !info.artists.isEmpty()) {
            List<String> copy = new ArrayList<>(info.artists);
            artistsField.set(songInfo, copy);
        }
    }
}
