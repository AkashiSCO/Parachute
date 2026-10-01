package com.create.parachute.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * 空气方块：<b>原版普通玻璃的复制品</b>，只把贴图整张改成全透明。
 *
 * <p>1.21.1 里原版玻璃就是 {@code net.minecraft.world.level.block.TransparentBlock}
 * （没有单独的 GlassBlock 类），{@code Blocks.GLASS = new TransparentBlock(...)}。
 * 这里直接继承它，所以下面这些玻璃行为全部是原版继承来的，一行都不用自己写：</p>
 * <ul>
 *   <li>{@code HalfTransparentBlock#skipRendering}：相邻同种方块之间不画内部面</li>
 *   <li>{@code TransparentBlock#getVisualShape}：视觉形状为空（不挡视线）</li>
 *   <li>{@code TransparentBlock#getShadeBrightness} → 1.0（不吃环境光遮蔽）</li>
 *   <li>{@code TransparentBlock#propagatesSkylightDown} → true（天空光直穿）</li>
 * </ul>
 *
 * <p>{@link #glassProperties()} 与 {@code Blocks.GLASS} 的构造参数逐项一致；
 * 贴图 {@code create_parachute:block/air_block} 是原版 {@code minecraft:block/glass}
 * 的像素、alpha 全为 0，配合模型里的 {@code "render_type": "minecraft:cutout"}
 * （原版玻璃在 1.21.1 的渲染层就是 {@code RenderType.cutout()}），方块放下去完全看不见。</p>
 */
public class AirBlock extends TransparentBlock {
    /** 序列化用：新建实例时走带 Properties 的构造器（和原版 TransparentBlock 同样的写法）。 */
    public static final MapCodec<AirBlock> CODEC = simpleCodec(AirBlock::new);

    public AirBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec() {
        return CODEC;
    }

    /** 原版 {@code Blocks.GLASS} 的属性，一字不差。 */
    public static BlockBehaviour.Properties glassProperties() {
        return BlockBehaviour.Properties.of()
                .instrument(NoteBlockInstrument.HAT)
                .strength(0.3F)
                .sound(SoundType.GLASS)
                .noOcclusion()
                .isValidSpawn((state, level, pos, entityType) -> false)
                .isRedstoneConductor((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false);
    }
}
