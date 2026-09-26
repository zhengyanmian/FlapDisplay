package com.flapdisplayplus.music.client;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.flapdisplayplus.music.source.LyricCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * CD 物品的封面渲染器。
 *
 * 让每张已命名的唱片（对应一首网易云歌曲）在物品栏/手中/掉落物里
 * 显示该歌曲的封面图，而不是统一的 CD 纹理。
 *
 * 封面未加载完成时回退到 Net Music 默认的 CD 纹理。
 */
public class CDCoverRenderer extends BlockEntityWithoutLevelRenderer {

    /** Net Music 默认 CD 纹理 */
    private static final ResourceLocation DEFAULT_CD =
            ResourceLocation.fromNamespaceAndPath("netmusic", "textures/item/music_cd.png");

    public CDCoverRenderer() {
        super(
                Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels()
        );
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx,
                             PoseStack pose, MultiBufferSource buffer, int light, int overlay) {
        ResourceLocation loc = resolveCover(stack);
        if (loc == null) {
            loc = DEFAULT_CD;
        }

        pose.pushPose();

        // GUI 上下文（物品栏）矩阵是像素坐标，需要放大到 16x16
        if (ctx == ItemDisplayContext.GUI) {
            pose.scale(16.0f, 16.0f, 1.0f);
        }

        // 画一个带封面纹理的方形面片
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(loc));
        float h = 0.5f;

        // 左下
        vc.addVertex(m, -h, -h, 0.0f).setUv(0.0f, 1.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f)
                .setLight(light).setOverlay(overlay).setNormal(0.0f, 0.0f, 1.0f);
        // 右下
        vc.addVertex(m, h, -h, 0.0f).setUv(1.0f, 1.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f)
                .setLight(light).setOverlay(overlay).setNormal(0.0f, 0.0f, 1.0f);
        // 右上
        vc.addVertex(m, h, h, 0.0f).setUv(1.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f)
                .setLight(light).setOverlay(overlay).setNormal(0.0f, 0.0f, 1.0f);
        // 左上
        vc.addVertex(m, -h, h, 0.0f).setUv(0.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f)
                .setLight(light).setOverlay(overlay).setNormal(0.0f, 0.0f, 1.0f);

        pose.popPose();
    }

    /**
     * 从 CD 物品提取歌曲 ID，返回封面纹理位置。
     * 封面未加载返回 null（调用方回退到默认 CD 纹理）。
     */
    private static ResourceLocation resolveCover(ItemStack stack) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(stack);
        if (info == null || info.songUrl == null) {
            return null;
        }
        long songId = LyricCache.extractSongId(info.songUrl);
        if (songId < 0) {
            return null;
        }
        return CoverCache.getLocation(songId);
    }
}
