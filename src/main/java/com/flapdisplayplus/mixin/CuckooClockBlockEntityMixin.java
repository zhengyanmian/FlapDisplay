/*
 * CuckooClockBlockEntityMixin.java
 *
 * 给 Create 布谷鸟时钟的 BlockEntity 增加「媒体配置」字段：
 * - mediaPath:  当前选择的媒体文件路径（flap-media/ 下的图片/GIF）
 * - displayMode: 显示模式（FIT/STRETCH/COVER）
 *
 * 通过注入 read/write 持久化到方块 NBT（存档不丢配置）。
 * 实现 CuckooClockMedia 接口供业务代码访问——业务代码只能引用接口，
 * 绝不能直接引用本 Mixin 类（会被 ASM 处理成 invalid）。
 */
package com.flapdisplayplus.mixin;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.api.CuckooClockMedia;
import com.simibubi.create.content.kinetics.clock.CuckooClockBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CuckooClockBlockEntity.class)
public abstract class CuckooClockBlockEntityMixin implements CuckooClockMedia {

    private static final String KEY_MEDIA_PATH = "flapdisplayplus_media_path";
    private static final String KEY_MEDIA_MODE = "flapdisplayplus_media_mode";
    private static final String KEY_SOURCE_TYPE = "flapdisplayplus_source_type";

    @Unique
    private String flapdisplayplus$mediaPath = "";

    @Unique
    private String flapdisplayplus$displayMode = "FIT";

    @Unique
    private String flapdisplayplus$sourceType = CuckooClockMedia.SOURCE_IMAGE;

    @Override
    public String flapdisplayplus$getMediaPath() {
        return flapdisplayplus$mediaPath;
    }

    @Override
    public void flapdisplayplus$setMediaPath(String path) {
        this.flapdisplayplus$mediaPath = path == null ? "" : path;
        ((CuckooClockBlockEntity) (Object) this).setChanged();
    }

    @Override
    public String flapdisplayplus$getDisplayMode() {
        return flapdisplayplus$displayMode;
    }

    @Override
    public void flapdisplayplus$setDisplayMode(String mode) {
        this.flapdisplayplus$displayMode = mode == null ? "FIT" : mode;
        ((CuckooClockBlockEntity) (Object) this).setChanged();
    }

    @Override
    public String flapdisplayplus$getSourceType() {
        return flapdisplayplus$sourceType;
    }

    @Override
    public void flapdisplayplus$setSourceType(String type) {
        this.flapdisplayplus$sourceType = type == null ? CuckooClockMedia.SOURCE_IMAGE : type;
        ((CuckooClockBlockEntity) (Object) this).setChanged();
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false)
    private void flapdisplayplus$read(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket, CallbackInfo ci) {
        try {
            if (tag.contains(KEY_MEDIA_PATH)) {
                this.flapdisplayplus$mediaPath = tag.getString(KEY_MEDIA_PATH);
            }
            if (tag.contains(KEY_MEDIA_MODE)) {
                this.flapdisplayplus$displayMode = tag.getString(KEY_MEDIA_MODE);
            }
            if (tag.contains(KEY_SOURCE_TYPE)) {
                this.flapdisplayplus$sourceType = tag.getString(KEY_SOURCE_TYPE);
            }
        } catch (Exception e) {
            FlapDisplayPlus.LOGGER.warn("[Cuckoo] 读取媒体配置失败", e);
        }
    }

    @Inject(method = "write", at = @At("HEAD"), remap = false)
    private void flapdisplayplus$write(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket, CallbackInfo ci) {
        try {
            tag.putString(KEY_MEDIA_PATH, this.flapdisplayplus$mediaPath);
            tag.putString(KEY_MEDIA_MODE, this.flapdisplayplus$displayMode);
            tag.putString(KEY_SOURCE_TYPE, this.flapdisplayplus$sourceType);
        } catch (Exception e) {
            FlapDisplayPlus.LOGGER.warn("[Cuckoo] 写入媒体配置失败", e);
        }
    }
}
