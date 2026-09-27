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
        // 深度偏移：默认 0.01（≈0.3 毫米）。斜看时「媒体与方块之间还有一点距离」的观感就来自它，
        // 已从早期的 1.0（= 1/32 方块）压到现在的量级；防共面闪烁由下面的 ZOffset 渲染类型承担。
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
        // 纯深度方向前移，无几何位移），保证稳定盖在面板之上，因此 z 可压到近乎贴面。
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
