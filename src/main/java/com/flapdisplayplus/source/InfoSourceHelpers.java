/*
 * InfoSourceHelpers.java
 *
 * 实时信息源辅助类（时间 / 天气 / TPS）：
 * 由 FlapDisplayMediaSource 在服务端调用，生成一行文本
 * 通过 Create 原版翻牌字符渲染显示在翻牌显示器上。
 */
package com.flapdisplayplus.source;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class InfoSourceHelpers {

    private InfoSourceHelpers() {
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 现实时间（日期 时间） */
    public static String realTimeText() {
        LocalDateTime now = LocalDateTime.now();
        return DATE_FMT.format(now) + " " + TIME_FMT.format(now);
    }

    /** 游戏内时间（MC 一天 24000 tick，tick 0 = 06:00） */
    public static String gameTimeText(Level level) {
        if (level == null) {
            return "??:??";
        }
        long dayTime = level.getDayTime() % 24000;
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }

    /** 游戏内天气 */
    public static String weatherText(Level level) {
        if (level == null) {
            return "§7（未知天气）";
        }
        if (level.isThundering()) {
            return "天气：雷暴 ⛈";
        }
        if (level.isRaining()) {
            return "天气：下雨 🌧";
        }
        return "天气：晴朗 ☀";
    }

    /** 服务端 TPS（每 tick 毫秒换算） */
    public static String tpsText(Level level) {
        if (level == null || level.getServer() == null) {
            return "§7（无服务器）";
        }
        MinecraftServer server = level.getServer();
        double avgTickMs = server.getAverageTickTime(); // ms/tick（1.20.1 只有毫秒粒度）
        double tps = avgTickMs > 0 ? Math.min(20.0, 1000.0 / avgTickMs) : 20.0;
        return String.format("TPS %.1f (%.1fms)", tps, avgTickMs);
    }
}
