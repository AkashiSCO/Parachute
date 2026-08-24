package com.create.parachute.registry;

import com.create.parachute.ExampleMod;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ExampleMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ParachuteBlockEntity>> PARACHUTE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("parachute_block_entity",
                    () -> BlockEntityType.Builder.of(ParachuteBlockEntity::new,
                            ModBlocks.PARACHUTE_BLOCK.get()).build(null));

    private ModBlockEntities() {
    }
}
