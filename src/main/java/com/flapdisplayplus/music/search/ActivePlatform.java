package com.flapdisplayplus.music.search;

/**
 * 刻录机界面上选中的当前搜索平台（网易云 / QQ音乐）。
 *
 * 由刻录机里的「平台切换」按钮设置，搜索界面读取它来决定用哪个搜索源、
 * 以及点选结果后以哪种方式解析/制作唱片：
 * - netease：网易云，搜索结果回填 song?id=数字，交给原版刻录机解析制作；
 * - qqmusic：QQ音乐，搜索结果回填 qqmusic:{mid}，由本模组拦截制作。
 *
 * 默认网易云。
 */
public final class ActivePlatform {
    /** 平台 ID：netease（网易云）或 qqmusic（QQ音乐） */
    public static String ID = "netease";

    private ActivePlatform() {
    }

    public static boolean isQq() {
        return "qqmusic".equals(ID);
    }

    /** 在网易云 / QQ音乐 之间互切 */
    public static void toggle() {
        ID = isQq() ? "netease" : "qqmusic";
    }

    public static String displayName() {
        return isQq() ? "QQ音乐" : "网易云音乐";
    }
}
