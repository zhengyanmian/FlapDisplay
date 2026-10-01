/*
 * FlapDisplayRendererMixin.java
 *
 * 翻牌显示器媒体叠加层 Mixin（客户端）。
 *
 * 注入到 FlapDisplayRenderer.renderSafe 的【外层 popPose 之前】——
 * 此时 PoseStack 仍处于 Create 自己搭好的「翻牌正面 2D 平面」坐标系内，
 * 我们抵消 Create 循环累积的逐行 translate，即可在同一平面叠加绘制媒体帧。
 *
 * 绘制内容由 MediaRenderRegistry 决定：该翻牌坐标对应的媒体配置
 * （媒体文件路径 + 显示模式），纹理由 MediaManager 按路径缓存。
 * 显示模式支持 FIT（保持比例完整）/ STRETCH（拉伸铺满）/ COVER（保持比例裁切铺满）。
 *
 * 注意：辅助绘制方法必须避开 Create 已有的同名方法（如 drawRect），
 * 否则 Mixin 织入后调用会解析到 Create 的实现导致格式不匹配崩溃。
 */
package com.flapdisplayplus.mixin;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.client.MediaManager;
import com.flapdisplayplus.client.MediaRenderRegistry;
import com.flapdisplayplus.config.Config;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlapDisplayRenderer.class)
public abstract class FlapDisplayRendererMixin {

    /**
     * 绘制诊断日志节流计数器。
     * 注：诊断日志一律用 debug 级别（默认不输出），避免稳定后仍每 100 帧刷日志。
     * 排障时在日志配置里把本模组调到 debug 即可恢复。
     */
    private static final java.util.concurrent.atomic.AtomicLong RENDER_LOG_THROTTLE =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 独立渲染缓冲：不借用 Create 传入的 MultiBufferSource。
     * 之前用 buffer.getBuffer(entityCutoutNoCull) 在 Create 渲染中途写顶点，
     * BufferBuilder 里残留缺 UV1 的顶点导致 endLastVertex 崩溃
     * （IllegalStateException: Missing elements in vertex: UV1）。
     * 独立 RenderBuffers + 画完立即 endBatch 提交，完全隔离 Create 的渲染状态。
     */
    @Unique
    private static final RenderBuffers FDP_RENDER_BUFFERS = new RenderBuffers(1536);

    @Inject(method = "renderSafe(Lcom/simibubi/create/content/trains/display/FlapDisplayBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                     ordinal = 1, shift = At.Shift.BEFORE))
    private void flapdisplayplus$renderMedia(FlapDisplayBlockEntity be, float partialTicks,
                                             PoseStack ms, MultiBufferSource buffer,
                                             int light, int overlay, CallbackInfo ci) {
        if (be == null) {
            return;
        }
        // ===== 遵循原版规则（2026-10-01 恢复）：翻牌需要转速（动力）才显示媒体 =====
        // 原版翻牌文字需要转速才翻动叶片；媒体画面虽是我们自己叠加绘制的一层，
        // 但按用户要求遵循原版设定：无转速（断电/停转/动力网络过载应力不足）时不渲染媒体。
        // 渲染心跳断流后，MediaManager 孤儿回收（5 秒）自动停掉视频与声音；
        // 恢复供能后 getVideoFrame 重建播放器从头播放。
        if (Math.abs(be.getSpeed()) < 0.01f) {
            return;
        }
        long n = RENDER_LOG_THROTTLE.incrementAndGet();
        // 入口诊断（每 100 帧，debug 级别）：判断 handler 是否真的被调用
        if (n % 100 == 0) {
            FlapDisplayPlus.LOGGER.debug("[RenderMedia] enter be={} isController={} xSize={} ySize={} registrySize={} keys={}",
                    be.getBlockPos(), be.isController, be.xSize, be.ySize,
                    MediaRenderRegistry.size(), MediaRenderRegistry.keys());
        }
        MediaRenderRegistry.MediaInfo info = MediaRenderRegistry.get(be.getBlockPos());
        if (info == null) {
            // 宽容匹配：registry key 是服务端 getController() 解析出的坐标，
            // 多块结构下可能与实际渲染的 controller 不一致（挖角/结构断开时
            // getController 不稳定）。遍历 registry，找与 be 同一显示带的 key：
            // key 处的翻牌 BE 的 getController() == be.getBlockPos()。
            if (be.getLevel() != null) {
                for (BlockPos key : MediaRenderRegistry.keys()) {
                    if (key.equals(be.getBlockPos())) continue;
                    try {
                        if (be.getLevel().getBlockEntity(key) instanceof FlapDisplayBlockEntity f2) {
                            FlapDisplayBlockEntity c = f2.getController();
                            if (c != null && c.getBlockPos().equals(be.getBlockPos())) {
                                info = MediaRenderRegistry.get(key);
                                if (info != null) {
                                    if (n % 200 == 0) {
                                        FlapDisplayPlus.LOGGER.debug("[RenderMedia] 宽容匹配: be={} key={}", be.getBlockPos(), key);
                                    }
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (info == null) {
            // 每 200 帧打一次（debug），定位为何 get 不到
            if (n % 200 == 0) {
                FlapDisplayPlus.LOGGER.debug("[RenderMedia] info=null be={} registryKeys={}",
                        be.getBlockPos(), MediaRenderRegistry.keys());
            }
            return;
        }
        // 当前帧纹理：视频走 getVideoFrame（启动真正出声的播放器，仅限被选中的视频）；
        // 动图按动画时间取帧（循环），静态图返回固定纹理。
        // 带上本翻牌的坐标：网页媒体用它做「显示点离玩家多远」的静音判据
        // （Create 的翻牌渲染不做视锥剔除，走远了照样取帧，只看心跳无法判断"看不见了"）。
        ResourceLocation frame = MediaManager.getVideoFrame(info.mediaPath, be.getBlockPos());
        if (frame == null) {
            if (n % 200 == 0) {
                FlapDisplayPlus.LOGGER.debug("[RenderMedia] frame=null path={}", info.mediaPath);
            }
            return;
        }

        // ===== 坐标系与可见面板几何 =====
        // Create 在画内容前先 scale(0.03125)，故注入点为【像素坐标系】：1 单位 = 1/32 方块。
        //   - 注入点（外层 popPose BEFORE）时行循环已跑完，PoseStack 的 y = 显示带底部
        //     （每行 translate(0,16,0) 累积，行内 translate 已被 popPose 撤销）
        //     → 必须先回退到顶部，否则矩形画在显示带【下方】被遮挡。
        //   - x=0 是显示带左缘，带宽 = xSize*32 单位，高 = lines*16 单位（lines = ySize*2）。
        //
        // 【2026-09-27 几何依据修正】
        // 上一版把「可见面板」当成 BakedGlyph.Effect(-1, 9, section.size, -2, 0.01) 画出的矩形，
        // 但 javap 确认该 Effect 位于 FlapDisplayRenderOutput.finish()，而 renderSafe 从不调用
        // finish() —— 它是死代码，不能当几何依据。
        // 真实依据是方块模型 create:block/display_board/block（create:display_board 的 blockstate
        // 属性 up/down/facing/waterlogged 与 FlapDisplayBlock 完全一致，确认就是翻牌）：
        //   正面元素 from[0,0,3] to[16,16,6] 覆盖【整块方块正面】，
        //   贴图 flap_display_front 全 16×16 不透明，无额外内框。
        // 即：整条显示带本身就是可见面板，没有硬编码内缩；具体偏差交给
        // media.insetX / media.insetY 控制（游戏内 /fdpcal 可实时调，无需重启）。
        int lines = Math.max(be.getLines().size(), Math.max(2, be.ySize * 2));
        float w = Math.max(1.0f, be.xSize) * 32f;   // 显示带宽（像素单位，x 从 0 到 w）
        float h = lines * 16f;                       // 显示带高（像素单位）

        // ===== 叠加层微调（可实时调整）=====
        // 单位统一为 1/32 方块（≈0.5 像素）：2 单位 = 1 像素。
        // insetX / insetY：相对整条显示带的内缩（正数向内，负数向外）。
        // offsetX / offsetY：整体平移。inset：四边再内缩一层（负数外扩）。
        double insetX = Config.MEDIA_INSET_X.get();
        double insetY = Config.MEDIA_INSET_Y.get();
        int cfgOffX = Config.MEDIA_MEDIA_OFFSET_X.get();
        int cfgOffY = Config.MEDIA_MEDIA_OFFSET_Y.get();
        int cfgInset = Config.MEDIA_MEDIA_INSET.get();
        // 【2026-09-27 深度基准修正 —— 「侧面看差半个像素」的真正根因】
        // create 6.0.10 renderSafe 字节码实测的坐标基准（内容坐标系，1 单位 = 1/32 方块）：
        //   内容 z = 0    → 翻牌字符平面。Create 把字符叶片画在面板【可见正面】前方 0.5 单位
        //                  （= 1/64 方块），目的是防止叶片与面板共面闪烁。
        //   内容 z = -0.5 → 与面板可见正面【完全共面】（推导：renderSafe 末尾
        //                  scale(0.03125) 后 translate(0,0,0.5)，配前面的 translate(0,0,-0.1875)
        //                  —— -3/16 正好等于方块模型 display_board/block 正面钢板的 z ——
        //                  得 方块z = 0.1875 - (内容z + 0.5)/32；内容 z=-0.5 时正好 = 3/16）。
        // 旧默认 z=0.01 落在字符平面上 ⇒ 媒体跟着前凸 0.5 单位 ⇒ 斜看时相对方块错开
        // 0.5×tan(视角) 单位（45° 时约半格像素，正是用户报的现象）。
        // x/y/inset 是平面内平移，属于常量偏移，【结构上】追不上随视角变化的视差 —— 所以调它们没用。
        // 现在默认 -0.49：与面板共面（视差 ≈ 0），留 0.01 单位作确定性余量。
        // 可以安全压回面板平面的原因：媒体显示源 provideLine() 返回 EMPTY_LINE，
        // 字符平面没有叶片（空格不产生字形四边形），不会被字符遮挡。
        float z = (float) (double) Config.MEDIA_Z_OFFSET.get();

        float x0 = (float) insetX + cfgOffX + cfgInset;
        float y0 = (float) insetY + cfgOffY + cfgInset;
        float bw = Math.max(1f, (float) (w - insetX * 2.0) - cfgInset * 2f);
        float bh = Math.max(1f, (float) (h - insetY * 2.0) - cfgInset * 2f);

        // 图片原始尺寸（FIT/COVER 比例计算）
        float iw = MediaManager.getTextureWidth(info.mediaPath);
        float ih = MediaManager.getTextureHeight(info.mediaPath);

        ms.pushPose();
        // 关键：回退到显示带顶部（注入点 y = 显示带底部），再叠加微调偏移与内缩
        ms.translate(x0, -lines * 16f + y0, 0);
        Matrix4f pose = ms.last().pose();
        // 独立 RenderBuffers（与 Create 的 buffer 隔离），画完立即 endBatch 提交。
        // 用 ZOffset 变体：它走 VIEW_OFFSET_Z_LAYERING（把 modelview 等比缩放 0.99975586，
        // 纯深度方向前移，无几何位移），保证稳定盖在面板之上 —— 正因如此才敢让媒体与面板
        // 【共面】(z = -0.49) 而不闪烁；共面 = 任意视角视差恒为 0，这才是消除「侧面半像素差距」的关键。
        RenderType rt = RenderType.entityCutoutNoCullZOffset(frame);
        VertexConsumer vc = FDP_RENDER_BUFFERS.bufferSource().getBuffer(rt);

        // x 从 0（显示带左缘）到 bw（右缘）；y 从顶部 0 到底部 bh
        // UV：面板顶部(y=0) ↔ v=0（纹理顶部）；底部 ↔ v=1（全图）
        String mode = info.displayMode == null ? "FIT" : info.displayMode;
        if ("STRETCH".equals(mode)) {
            fdpRenderMediaQuad(vc, pose, 0, 0, bw, bh, z, 0, 0, 1, 1, light, overlay);
        } else if ("COVER".equals(mode) && iw > 0 && ih > 0) {
            // 保持宽高比，裁切铺满：计算 UV 窗口
            float targetRatio = bw / bh;
            float srcRatio = iw / ih;
            if (srcRatio > targetRatio) {
                // 图片更宽：裁左右，u 从中间取
                float uHalf = (targetRatio / srcRatio) / 2f;
                fdpRenderMediaQuad(vc, pose, 0, 0, bw, bh, z, 0.5f - uHalf, 0, 0.5f + uHalf, 1, light, overlay);
            } else {
                // 图片更高：裁上下，v 从中间取
                float vHalf = (srcRatio / targetRatio) / 2f;
                fdpRenderMediaQuad(vc, pose, 0, 0, bw, bh, z, 0, 0.5f - vHalf, 1, 0.5f + vHalf, light, overlay);
            }
        } else {
            // FIT（默认）：保持宽高比完整显示，居中留边
            if (iw > 0 && ih > 0) {
                float targetRatio = bw / bh;
                float srcRatio = iw / ih;
                if (srcRatio > targetRatio) {
                    // 以宽为基准，上下留边
                    float drawH = bw / srcRatio;
                    float yOff = (bh - drawH) / 2f;
                    fdpRenderMediaQuad(vc, pose, 0, yOff, bw, yOff + drawH, z, 0, 0, 1, 1, light, overlay);
                } else {
                    // 以高为基准，左右留边
                    float drawW = bh * srcRatio;
                    float xOff = (bw - drawW) / 2f;
                    fdpRenderMediaQuad(vc, pose, xOff, 0, xOff + drawW, bh, z, 0, 0, 1, 1, light, overlay);
                }
            } else {
                fdpRenderMediaQuad(vc, pose, 0, 0, bw, bh, z, 0, 0, 1, 1, light, overlay);
            }
        }

        ms.popPose();

        // 立即提交本帧图片（否则独立 buffer 不会在帧末自动 flush）
        FDP_RENDER_BUFFERS.bufferSource().endBatch(rt);

        // 诊断：每 100 帧打印一次绘制参数（debug），便于定位几何问题（复用开头已 increment 的 n）
        if (n % 100 == 0) {
            FlapDisplayPlus.LOGGER.debug("[RenderMedia] 绘制 flap={} band={}x{} 原点=({},{}) 有效={}x{} insetX/Y={}/{} inset={} off=({},{}) z={} lines={} xSize={} ySize={} mode={}",
                    be.getBlockPos(), w, h, x0, y0, bw, bh, insetX, insetY, cfgInset, cfgOffX, cfgOffY, z,
                    lines, be.xSize, be.ySize, mode);
        }
    }

    /**
     * 纹理矩形辅助方法。
     * 必须 @Unique（Mixin 织入时加 $ 前缀进目标类）+ 绝对唯一命名：
     * Create 的 FlapDisplayRenderer 自己有 drawRect 和 flapDrawMediaQuad 私有方法，
     * 非 @Unique 的同名方法会被混入目标类并与 Create 实现冲突（渲染期崩溃，
     * 调用被解析到 Create 版本）。
     * u0/v0 是左上角 UV，u1/v1 是右下角 UV（v 向下）。
     * 链式调用顺序严格匹配 NEW_ENTITY 格式：
     *   POSITION -> COLOR -> UV0(纹理) -> UV1(overlay) -> UV2(lightmap) -> NORMAL。
     * 注意：1.21.1 中 setLight() 写 UV2(lightmap)，UV1(overlay) 必须用 setOverlay() 单独写，
     * 否则 endLastVertex 抛 "Missing elements in vertex: UV1"。
     */
    @Unique
    private static void fdpRenderMediaQuad(VertexConsumer vc, Matrix4f pose,
                                           float x0, float y0, float x1, float y1, float z,
                                           float u0, float v0, float u1, float v1, int light, int overlay) {
        vc.addVertex(pose, x0, y0, z).setColor(1.0f, 1.0f, 1.0f, 1.0f).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(0.0f, 0.0f, 1.0f);
        vc.addVertex(pose, x1, y0, z).setColor(1.0f, 1.0f, 1.0f, 1.0f).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(0.0f, 0.0f, 1.0f);
        vc.addVertex(pose, x1, y1, z).setColor(1.0f, 1.0f, 1.0f, 1.0f).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(0.0f, 0.0f, 1.0f);
        vc.addVertex(pose, x0, y1, z).setColor(1.0f, 1.0f, 1.0f, 1.0f).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(0.0f, 0.0f, 1.0f);
    }
}
