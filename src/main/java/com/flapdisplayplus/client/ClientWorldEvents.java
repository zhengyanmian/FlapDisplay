/*
 * ClientWorldEvents.java
 *
 * 客户端世界生命周期事件（注册到 Forge 游戏总线）：
 * - 退出世界 / 断线（回到主菜单或离开服务器）：停止所有视频播放器并清空渲染注册表。
 *   关键修复：VideoPlayer 的音频/解码线程是守护线程，世界卸载时 JVM 仍在运行，
 *   若不在此显式 stop，音频线会一直播放、解码线程持续占 CPU，只有完全退出游戏才停。
 * - 每刻扫描：清理「已暂停且长时间无人观看」的闲置视频播放器，防止 VIDEOS Map
 *   只增不减造成的内存/原生资源（SourceDataLine）泄漏与僵尸复播。
 *
 * 【Forge 移植说明】NeoForge 1.21 的 LevelTickEvent.Post / ClientTickEvent.Post
 * 对应 Forge 1.20.1 的 TickEvent.LevelTickEvent / TickEvent.ClientTickEvent，
 * 用 phase == Phase.END 等价 Post。
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;

public final class ClientWorldEvents {

    private ClientWorldEvents() {
    }

    /** 在客户端初始化时调用，把处理器注册到 Forge 事件总线 */
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientWorldEvents::onLevelUnload);
        MinecraftForge.EVENT_BUS.addListener(ClientWorldEvents::onLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ClientWorldEvents::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientWorldEvents::onLevelTick);
    }

    /**
     * 每客户端刻：ESC 暂停联动（视频 + 网页媒体一起冻结）。
     *
     * ★ 必须用 ClientTickEvent 而不是 LevelTickEvent：单机按 ESC 后客户端关卡 tick
     *   直接停掉，挂在 LevelTickEvent 上的暂停检测在暂停期间永远不会执行 —— 这正是
     *   v1.0.7「暂停不生效」的根因（日志里连一条「游戏菜单暂停」都没有）。
     */
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MediaManager.tickPauseWatch();
        }
    }

    /** 退出世界（回到主菜单）：客户端关卡卸载即触发 */
    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            MediaManager.stopAllVideos();
            MediaRenderRegistry.clearAll();
        }
    }

    /** 断线 / 登出（离开服务器或单人世界关闭） */
    private static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MediaManager.stopAllVideos();
        MediaRenderRegistry.clearAll();
    }

    /** 每刻扫描：清理闲置超时的视频播放器（防泄漏与僵尸复播） */
    private static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level.isClientSide()) {
            MediaManager.tick();
        }
    }
}
