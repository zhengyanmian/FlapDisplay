/*
 * ServerBlockEvents.java
 *
 * 服务端方块事件（注册在 NeoForge 游戏总线上）：
 *
 * 【2026-09-27 新增 —— 修「拆掉方块后视频/图片还在显示」】
 * 媒体叠加的清除原本只依赖两条路径：
 *   ① 链接器 tick 里发现「源/目标失效」→ 发清空包；
 *   ② 客户端注册表 8 秒过期。
 * 两者都覆盖不到「玩家把【显示链接器】挖掉」这一种：
 * 链接器 BE 一旦被移除，它的 tick 自然不再执行，没有任何包会发出去，
 * 于是翻牌上会一直留着最后那帧画面（最长到客户端过期为止）。
 *
 * 这里监听 BlockEvent.BreakEvent（拆除前触发，此时 BE 还在，可以读到它的
 * source/target 绑定），把对应翻牌坐标的媒体清空包直接发给附近玩家 —— 立即生效。
 *
 * 注意：本事件只在**逻辑服务端**触发；单人世界由客户端内嵌服务端触发，
 * 专用服务器由服务器自身触发，因此本类在构造函数里无条件注册即可。
 */
package com.flapdisplayplus.event;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.network.MediaDisplayPacket;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerBlockEvents {

    private ServerBlockEvents() {
    }

    /** 方块被拆除：若是显示链接器，立即清空它指向的翻牌上的媒体叠加 */
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return; // 只在逻辑服务端处理
        }
        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DisplayLinkBlockEntity link)) {
            return; // 拆的不是显示链接器：交给其它机制（客户端核对方块 / 过期）
        }
        try {
            BlockPos target = link.getTargetPosition();
            if (target == null) {
                return;
            }
            BlockEntity tbe = level.getBlockEntity(target);
            if (!(tbe instanceof FlapDisplayBlockEntity fbe)) {
                return;
            }
            // 与 FlapDisplayMediaSource 一致：媒体渲染坐标是显示带的 controller（左上角）
            FlapDisplayBlockEntity controller = fbe.getController();
            BlockPos renderPos = (controller != null ? controller : fbe).getBlockPos();
            PacketDistributor.sendToPlayersNear(level, null,
                    renderPos.getX() + 0.5, renderPos.getY() + 0.5, renderPos.getZ() + 0.5, 64.0,
                    new MediaDisplayPacket(renderPos, "", "FIT"));
            FlapDisplayPlus.LOGGER.debug("[ServerBlockEvents] 显示链接器 {} 被拆，已清空翻牌 {} 的媒体",
                    pos, renderPos);
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[ServerBlockEvents] 拆除时清空媒体失败: {}", pos, t);
        }
    }
}
