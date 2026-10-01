package com.create.parachute.registry;

import com.create.parachute.ParachuteMod;
import com.create.parachute.block.AirBlock;
import com.create.parachute.parachute.ParachuteBlock;
import com.create.parachute.parachute.ParachutePackItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
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
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ParachuteMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ParachuteMod.MOD_ID);

    public static final DeferredBlock<Block> PARACHUTE_BLOCK = BLOCKS.register("parachute_block", () -> new ParachuteBlock());
    public static final DeferredItem<Item> PARACHUTE_BLOCK_ITEM = ITEMS.register("parachute_block",
            () -> new ParachutePackItem(PARACHUTE_BLOCK.get(), new Item.Properties()));

    /**
     * 空气方块：原版普通玻璃（TransparentBlock）的复制品，贴图整张全透明。
     * 渲染层由模型 JSON 的 {@code "render_type": "minecraft:cutout"} 指定（和原版玻璃一致），
     * 不需要额外的客户端代码。
     */
    public static final DeferredBlock<Block> AIR_BLOCK = BLOCKS.register("air_block",
            () -> new AirBlock(AirBlock.glassProperties()));
    /**
     * 空气方块物品。
     *
     * <p><b>附魔物品效果（紫色附魔闪光）</b>：用原版 1.21 的
     * {@link DataComponents#ENCHANTMENT_GLINT_OVERRIDE} 组件写死为 {@code true}。
     * {@code ItemStack#hasFoil()} 的实现是
     * {@code Boolean b = get(ENCHANTMENT_GLINT_OVERRIDE); return b != null ? b : getItem().isFoil(this);}，
     * 所以只要这个组件存在就永远发光，和「附魔书/附魔装备」看到的光效完全一样，
     * 而且不需要自定义 Item 子类、不需要客户端代码。</p>
     *
     * <p>注意：闪光只在<b>物品形态</b>（背包、手持、物品展示框、掉落物等）出现；
     * 方块放进世界里之后不会再发光——原版方块本身没有附魔光效这一说。</p>
     */
    public static final DeferredItem<Item> AIR_BLOCK_ITEM = ITEMS.register("air_block",
            () -> new BlockItem(AIR_BLOCK.get(), new Item.Properties()
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

    private ModBlocks() {
    }
}
