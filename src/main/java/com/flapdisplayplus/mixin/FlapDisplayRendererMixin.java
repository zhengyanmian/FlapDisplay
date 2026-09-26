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
        // 动图按动画时间取帧（循环），静态图返回固定纹理
        ResourceLocation frame = MediaManager.getVideoFrame(info.mediaPath);
        if (frame == null) {
            if (n % 200 == 0) {
                FlapDisplayPlus.LOGGER.debug("[RenderMedia] frame=null path={}", info.mediaPath);
            }
            return;
        }

        // ===== 坐标系（反编译 renderSafe + FlapDisplaySection 字节码确认）=====
        // Create 在画内容前先 scale(0.03125)，故注入点为【像素坐标系】：
        //   - 注入点（panel popPose BEFORE）时：行循环已跑完，PoseStack 的 y = 显示带底部
        //     （每行 translate(0,16,0) 累积）！必须回退到顶部再画，否则矩形画在显示带
        //     【下方】被遮挡（"挖角才显示"根因）。
        //   - x=0 是显示带左缘（controller 左缘），显示带向右延伸，宽 = xSize*32 单位
        //     （xSize*16 是字符数基准：xSize 块 × 16 字符/块 × 2 单位/字符 = xSize*32）
        //   - 总行数 lines = ySize*2（每块 2 行），高 = lines*16 单位
        //   - z=0.5 为面板前沿（translate(0,0,0.5) 之后）
        int lines = Math.max(be.getLines().size(), Math.max(2, be.ySize * 2));
        float w = Math.max(1.0f, be.xSize) * 32f;   // 显示带宽（像素单位，x 从 0 到 w）
        float h = lines * 16f;                       // 显示带高（像素单位）
        float z = 1.0f;                              // 面板前沿 0.5，取 1.0 防共面闪烁

        // 图片原始尺寸（FIT/COVER 比例计算）
        float iw = MediaManager.getTextureWidth(info.mediaPath);
        float ih = MediaManager.getTextureHeight(info.mediaPath);

        ms.pushPose();
        // 关键：回退到显示带顶部（当前 y = 显示带底部）
        ms.translate(0, -lines * 16f, 0);
        Matrix4f pose = ms.last().pose();
        // 独立 RenderBuffers（与 Create 的 buffer 隔离），画完立即 endBatch 提交
        RenderType rt = RenderType.entityCutoutNoCull(frame);
        VertexConsumer vc = FDP_RENDER_BUFFERS.bufferSource().getBuffer(rt);

        // x 从 0（显示带左缘）到 w（右缘）；y 从顶部 0 到底部 h
        // UV：面板顶部(y=0) ↔ v=0（纹理顶部）；底部 ↔ v=1（全图）
        String mode = info.displayMode == null ? "FIT" : info.displayMode;
        if ("STRETCH".equals(mode)) {
            fdpRenderMediaQuad(vc, pose, 0, 0, w, h, z, 0, 0, 1, 1, light, overlay);
        } else if ("COVER".equals(mode) && iw > 0 && ih > 0) {
            // 保持宽高比，裁切铺满：计算 UV 窗口
            float targetRatio = w / h;
            float srcRatio = iw / ih;
            if (srcRatio > targetRatio) {
                // 图片更宽：裁左右，u 从中间取
                float uHalf = (targetRatio / srcRatio) / 2f;
                fdpRenderMediaQuad(vc, pose, 0, 0, w, h, z, 0.5f - uHalf, 0, 0.5f + uHalf, 1, light, overlay);
            } else {
                // 图片更高：裁上下，v 从中间取
                float vHalf = (srcRatio / targetRatio) / 2f;
                fdpRenderMediaQuad(vc, pose, 0, 0, w, h, z, 0, 0.5f - vHalf, 1, 0.5f + vHalf, light, overlay);
            }
        } else {
            // FIT（默认）：保持宽高比完整显示，居中留边
            if (iw > 0 && ih > 0) {
                float targetRatio = w / h;
                float srcRatio = iw / ih;
                if (srcRatio > targetRatio) {
                    // 以宽为基准，上下留边
                    float drawH = w / srcRatio;
                    float yOff = (h - drawH) / 2f;
                    fdpRenderMediaQuad(vc, pose, 0, yOff, w, yOff + drawH, z, 0, 0, 1, 1, light, overlay);
                } else {
                    // 以高为基准，左右留边
                    float drawW = h * srcRatio;
                    float xOff = (w - drawW) / 2f;
                    fdpRenderMediaQuad(vc, pose, xOff, 0, xOff + drawW, h, z, 0, 0, 1, 1, light, overlay);
                }
            } else {
                fdpRenderMediaQuad(vc, pose, 0, 0, w, h, z, 0, 0, 1, 1, light, overlay);
            }
        }

        ms.popPose();

        // 立即提交本帧图片（否则独立 buffer 不会在帧末自动 flush）
        FDP_RENDER_BUFFERS.bufferSource().endBatch(rt);

        // 诊断：每 100 帧打印一次绘制参数（debug），便于定位几何问题（复用开头已 increment 的 n）
        if (n % 100 == 0) {
            FlapDisplayPlus.LOGGER.debug("[RenderMedia] 绘制 flap={} w={} h={} lines={} xSize={} ySize={} mode={}",
                    be.getBlockPos(), w, h, lines, be.xSize, be.ySize, mode);
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
