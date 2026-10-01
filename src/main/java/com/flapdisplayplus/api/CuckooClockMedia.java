/*
 * CuckooClockMedia.java
 *
 * 布谷鸟时钟媒体配置访问接口。
 *
 * 由 CuckooClockBlockEntityMixin 实现（注入到 Create 的布谷鸟时钟 BlockEntity）。
 * 业务代码（GUI / DisplaySource / 网络包）只依赖本接口，绝不直接引用 Mixin 类——
 * Mixin 类经 ASM 处理后无法作为普通类加载，直接引用会导致
 * NoClassDefFoundError: xxx is invalid。
 *
 * 注意：本接口【必须】放在普通包（api/），不能放 mixin 包——
 * mixin 包内所有类都被 Mixin 系统标记，同样无法被业务代码引用。
 */
package com.flapdisplayplus.api;

public interface CuckooClockMedia {

    /** 媒体源类型常量 */
    String SOURCE_IMAGE = "IMAGE";                 // 图片/GIF 媒体文件（默认）
    String SOURCE_INFO_TIME = "INFO_TIME";         // 实时信息：现实时间
    String SOURCE_INFO_GAME_TIME = "INFO_GAME_TIME"; // 实时信息：游戏内时间
    String SOURCE_INFO_WEATHER = "INFO_WEATHER";   // 实时信息：天气
    String SOURCE_INFO_TPS = "INFO_TPS";           // 实时信息：TPS

    /** 读取媒体路径（空 = 未配置，翻牌走原版字符显示） */
    String flapdisplayplus$getMediaPath();

    /** 设置媒体路径 */
    void flapdisplayplus$setMediaPath(String path);

    /** 读取显示模式（FIT / STRETCH / COVER） */
    String flapdisplayplus$getDisplayMode();

    /** 设置显示模式 */
    void flapdisplayplus$setDisplayMode(String mode);

    /** 读取源类型（SOURCE_* 常量，默认 SOURCE_IMAGE） */
    String flapdisplayplus$getSourceType();

    /** 设置源类型 */
    void flapdisplayplus$setSourceType(String type);
}
