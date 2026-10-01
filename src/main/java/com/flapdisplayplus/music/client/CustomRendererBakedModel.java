package com.flapdisplayplus.music.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraftforge.client.model.BakedModelWrapper;

/**
 * 包装 CD 物品的 BakedModel，强制 isCustomRenderer() 返回 true。
 *
 * Minecraft 的 BEWLR 机制要求物品模型的 BakedModel#isCustomRenderer() 返回 true
 * 才会走自定义渲染分支。Net Music 的 CD 模型是 item/generated（返回 false），
 * 所以用这个包装器强制启用自定义渲染，让封面渲染器生效。
 */
public class CustomRendererBakedModel extends BakedModelWrapper<BakedModel> {

    public CustomRendererBakedModel(BakedModel original) {
        super(original);
    }

    @Override
    public boolean isCustomRenderer() {
        return true;
    }
}
