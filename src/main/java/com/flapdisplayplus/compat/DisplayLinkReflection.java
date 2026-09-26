/*
 * DisplayLinkReflection.java
 *
 * 对 Create 显示链接器（DisplayLinkBlockEntity）的 activeSource 字段做安全的反射读写。
 *
 * 为什么用反射、且只用 getDeclaredField：
 *  - DisplayLinkBlockEntity 的类签名引用了 ComputerCraft 的 IPeripheral（Create 带 CC 兼容层）。
 *    若用 getDeclaredMethod 枚举方法，`getDeclaredMethods0` 会触发加载 IPeripheral → 未装 CC
 *    → NoClassDefFoundError（Error，catch(Exception) 接不住）→ 类 <clinit> 失败 → 崩溃。
 *  - getDeclaredField 只定位单个字段、只加载字段类型 DisplaySource（已安装），不会枚举方法，
 *    因此不会去碰 CC，安全。
 *
 * 为什么懒加载（不在 static 初始化块里做）：
 *  - 任何在 <clinit> 期的反射若意外抛 Error 都会让整类初始化失败；改为首次调用时才解析字段，
 *    失败也只是字段为 null（no-op），绝不让游戏崩溃。
 */
package com.flapdisplayplus.compat;

import com.flapdisplayplus.FlapDisplayPlus;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;

import java.lang.reflect.Field;

public final class DisplayLinkReflection {

    private DisplayLinkReflection() {
    }

    private static volatile Field ACTIVE_SOURCE_FIELD;
    private static volatile boolean INITIALIZED = false;

    private static Field resolveField() {
        if (INITIALIZED) {
            return ACTIVE_SOURCE_FIELD;
        }
        synchronized (DisplayLinkReflection.class) {
            if (INITIALIZED) {
                return ACTIVE_SOURCE_FIELD;
            }
            Field f = null;
            try {
                // 只取单个字段，不枚举方法 -> 不会加载 ComputerCraft 的 IPeripheral
                f = DisplayLinkBlockEntity.class.getDeclaredField("activeSource");
                f.setAccessible(true);
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[DisplayLink] 反射定位 activeSource 字段失败", t);
            }
            ACTIVE_SOURCE_FIELD = f;
            INITIALIZED = true;
            return f;
        }
    }

    /** 读取显示链接器当前活动显示源（null = 未选源） */
    public static DisplaySource getActiveSource(DisplayLinkBlockEntity link) {
        Field f = resolveField();
        if (f == null) {
            return null;
        }
        try {
            return (DisplaySource) f.get(link);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 设置显示链接器的活动显示源 */
    public static void setActiveSource(DisplayLinkBlockEntity link, DisplaySource source) {
        Field f = resolveField();
        if (f == null) {
            return;
        }
        try {
            f.set(link, source);
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[DisplayLink] 反射写入 activeSource 失败", t);
        }
    }
}
