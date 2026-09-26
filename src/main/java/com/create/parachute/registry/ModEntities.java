package com.create.parachute.registry;

import com.create.parachute.ParachuteMod;
import com.create.parachute.parachute.ParachuteSeatEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 实体注册：目前只有坐垫伞包的"座位"实体（不可见，只为让玩家能坐上去）。
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ParachuteMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<ParachuteSeatEntity>> PARACHUTE_SEAT =
            ENTITY_TYPES.register("parachute_seat", () -> EntityType.Builder
                    .<ParachuteSeatEntity>of(ParachuteSeatEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    // 不需要存档（玩家重新右键即可），也不允许 /summon 刷出来
                    .noSave()
                    .noSummon()
                    .build("parachute_seat"));

    private ModEntities() {
    }
}
