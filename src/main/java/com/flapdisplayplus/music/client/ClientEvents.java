package com.flapdisplayplus.music.client;

import com.github.tartaricacid.netmusic.init.InitItems;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.MusicNetIntegration;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * 客户端事件：注册 CD 物品的自定义渲染器（封面显示）。
 */
@EventBusSubscriber(modid = FlapDisplayPlus.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    /** Net Music CD 物品模型的 ResourceLocation */
    private static final ResourceLocation CD_MODEL =
            ResourceLocation.fromNamespaceAndPath("netmusic", "item/music_cd");

    /** 封面渲染器单例（BEWLR 是重量级对象，复用） */
    private static final BlockEntityWithoutLevelRenderer COVER_RENDERER = new CDCoverRenderer();

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        // 软联动：未安装网络音乐机时不注册（避免 NoClassDefFoundError）
        if (!MusicNetIntegration.isNetMusicLoaded()) {
            return;
        }
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return COVER_RENDERER;
            }
        }, InitItems.MUSIC_CD.get());
    }

    /**
     * 替换 CD 物品的 BakedModel，强制 isCustomRenderer() 返回 true。
     * 这样 Minecraft 才会调用我们注册的 BEWLR（封面渲染器）。
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        BakedModel original = event.getModels().get(ModelResourceLocation.inventory(CD_MODEL));
        if (original != null) {
            event.getModels().put(ModelResourceLocation.inventory(CD_MODEL), new CustomRendererBakedModel(original));
        }
    }
}
