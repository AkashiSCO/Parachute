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
                    // 尺寸和机械动力 SeatEntity 一样（sized(0.25f, 0.35f)）：座位实体不参与推挤
                    // （见 ParachuteSeatEntity 里 noPhysics / isPushable 等覆写），盒子只是给骑乘和
                    // Sable 的实体系统一个正常的体积。
                    .sized(0.25F, 0.35F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    // 不需要存档（玩家重新右键即可），也不允许 /summon 刷出来
                    .noSave()
                    .noSummon()
                    .build("parachute_seat"));

    /*
     * 注意：座位实体还必须在 Sable 的数据包标签里登记（和机械动力的 create:seat 一样），
     * 否则 Sable 不会把"船上的座位"当成物理体的一部分，坐在上面的玩家会被换算到错误的位置：
     *   data/sable/tags/entity_type/retain_in_sub_level.json
     *   data/sable/tags/entity_type/destroy_with_sub_level.json
     */
    private ModEntities() {
    }
}
