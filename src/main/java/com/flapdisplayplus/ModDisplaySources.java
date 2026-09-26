/*
 * ModDisplaySources.java
 *
 * 注册自定义 DisplaySource 到 Create 的 DISPLAY_SOURCE 注册表，
 * 并把「媒体显示源」绑定到布谷鸟时钟（CuckooClockBlockEntity）类型——
 * 玩家把显示链接器指向布谷鸟时钟即可使用。
 *
 * 绑定时机：必须在 Create 的注册全部完成之后（FMLCommonSetupEvent），
 * 否则 AllBlockEntityTypes.CUCKOO_CLOCK.get() 会抛 Unbound 异常。
 */
package com.flapdisplayplus;

import com.flapdisplayplus.source.FlapDisplayMediaSource;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModDisplaySources {
    public static final DeferredRegister<DisplaySource> DISPLAY_SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, FlapDisplayPlus.MODID);

    public static final DeferredHolder<DisplaySource, FlapDisplayMediaSource> FLAP_DISPLAY_MEDIA =
            DISPLAY_SOURCES.register("flap_display_media", FlapDisplayMediaSource::new);

    public static void register(IEventBus modBus) {
        DISPLAY_SOURCES.register(modBus);
        modBus.addListener(ModDisplaySources::onCommonSetup);
    }

    /** Create 注册完成后，把媒体显示源绑定到布谷鸟时钟 */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModDisplaySources::bindCuckooClockSource);
    }

    /** 把媒体显示源绑定到布谷鸟时钟的 BlockEntity 类型 */
    public static void bindCuckooClockSource() {
        try {
            if (AllBlockEntityTypes.CUCKOO_CLOCK != null) {
                // 必须用注册表里同一个实例（FLAP_DISPLAY_MEDIA.get()），
                // 否则 updateGatheredData 里 DisplaySource.getAll(...).contains(activeSource)
                // 用引用比较会判 false，把 activeSource 清空，永远不推送。
                FlapDisplayMediaSource mediaSource = FLAP_DISPLAY_MEDIA.get();
                if (mediaSource != null) {
                    DisplaySource.BY_BLOCK_ENTITY.add(
                            AllBlockEntityTypes.CUCKOO_CLOCK.get(),
                            mediaSource);
                    FlapDisplayPlus.LOGGER.info("[{}] 媒体显示源已绑定到布谷鸟时钟", FlapDisplayPlus.MODID);
                } else {
                    FlapDisplayPlus.LOGGER.warn("[{}] 媒体显示源尚未注册，绑定推迟", FlapDisplayPlus.MODID);
                }
            }
        } catch (Exception e) {
            FlapDisplayPlus.LOGGER.warn("[{}] 绑定布谷鸟时钟显示源失败", FlapDisplayPlus.MODID, e);
        }
    }
}
