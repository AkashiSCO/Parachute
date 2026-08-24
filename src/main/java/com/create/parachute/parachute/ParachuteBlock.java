package com.create.parachute.parachute;

import com.create.parachute.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class ParachuteBlock extends BaseEntityBlock {
    public static final MapCodec<ParachuteBlock> CODEC = simpleCodec(properties -> new ParachuteBlock());
    public static final BooleanProperty DEPLOYED = BooleanProperty.create("deployed");
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final VoxelShape SHAPE_UP = Block.box(4, 0, 4, 12, 4, 12);
    private static final VoxelShape SHAPE_DOWN = Block.box(4, 12, 4, 12, 16, 12);
    private static final VoxelShape SHAPE_NORTH = Block.box(4, 4, 12, 12, 12, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(4, 4, 0, 12, 12, 4);
    private static final VoxelShape SHAPE_WEST = Block.box(12, 4, 4, 16, 12, 12);
    private static final VoxelShape SHAPE_EAST = Block.box(0, 4, 4, 4, 12, 12);
    private static final VoxelShape[] SHAPES_BY_DIR = {
        SHAPE_DOWN, SHAPE_UP, SHAPE_NORTH, SHAPE_SOUTH, SHAPE_WEST, SHAPE_EAST
    };

    public ParachuteBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(1.5F)
                .noOcclusion()
                .requiresCorrectToolForDrops());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(DEPLOYED, false)
                .setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DEPLOYED, FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                   CollisionContext context) {
        return SHAPES_BY_DIR[state.getValue(FACING).ordinal()];
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        // 客户端直接打开控制器 GUI（伞选择界面）；服务端无需操作
        if (level.isClientSide) {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.create.parachute.client.ParachuteScreen(pos));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.getItem() instanceof DyeItem dyeItem) {
            if (!level.isClientSide) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof ParachuteBlockEntity pbe) {
                    pbe.setDyeColor(dyeItem.getDyeColor());
                    level.playSound(null, pos, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        // 斧头右键：清除染色，恢复 .bbmodel 原始贴图
        if (stack.getItem() instanceof AxeItem) {
            if (!level.isClientSide) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof ParachuteBlockEntity pbe) {
                    pbe.clearDye();
                    level.playSound(null, pos, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ParachuteBlockEntity pbe) {
            pbe.checkRedstone();
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ParachuteBlockEntity pbe) {
                pbe.checkRedstone();
            }
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ParachuteBlockEntity be) {
            return be.isDeployed() ? 15 : 0;
        }
        return 0;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ParachuteBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.PARACHUTE_BLOCK_ENTITY.get(),
                level.isClientSide ? ParachuteBlockEntity::clientTick : ParachuteBlockEntity::serverTick);
    }
}
