package com.create.parachute.registry;

import com.create.parachute.ExampleMod;
import com.create.parachute.parachute.ParachuteBlock;
import com.create.parachute.parachute.ParachutePackItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块/物品注册：现在只有一个伞包方块 + 一个伞包物品。
 * 伞的型号由伞名（parachute/ 文件夹）决定，通过 GUI 选择并存入 NBT。
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ExampleMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExampleMod.MOD_ID);

    public static final DeferredBlock<Block> PARACHUTE_BLOCK = BLOCKS.register("parachute_block", () -> new ParachuteBlock());
    public static final DeferredItem<Item> PARACHUTE_BLOCK_ITEM = ITEMS.register("parachute_block",
            () -> new ParachutePackItem(PARACHUTE_BLOCK.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
