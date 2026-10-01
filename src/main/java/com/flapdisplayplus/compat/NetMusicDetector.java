/*
 * NetMusicDetector.java
 *
 * 网络音乐机(Net Music)软联动探测：
 * 运行时用 Class.forName 探测 Net Music 的类是否存在（是否安装），
 * 不安装则整个音乐联动层跳过，模组其余功能不受影响。
 *
 * 注意：这里【绝不能】在源码里直接引用 Net Music 的类（会触发硬依赖），
 * 只能通过反射字符串访问。
 */
package com.flapdisplayplus.compat;

public final class NetMusicDetector {

    /** Net Music 客户端 GUI 类（存在即可判定已安装） */
    private static final String[] PROBE_CLASSES = {
            "com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen",
            "com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer"
    };

    private static Boolean installed;

    private NetMusicDetector() {}

    /** Net Music 是否已安装（缓存探测结果） */
    public static boolean isInstalled() {
        if (installed == null) {
            installed = probe();
        }
        return installed;
    }

    private static boolean probe() {
        for (String cls : PROBE_CLASSES) {
            try {
                Class.forName(cls, false, NetMusicDetector.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
                // 继续探测下一个
            }
        }
        return false;
    }

    /** 调试用：强制重新探测 */
    public static void reset() {
        installed = null;
    }
}
