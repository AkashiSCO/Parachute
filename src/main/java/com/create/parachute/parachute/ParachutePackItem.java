package com.create.parachute.parachute;

import com.create.parachute.client.ClientHooks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 伞包物品：唯一的伞方块物品。
 * <ul>
 *   <li>右键空气：打开伞选择界面（写入物品 NBT）</li>
 *   <li>放置方块：把物品 NBT 里的伞名带入方块实体</li>
 *   <li>所有伞均可染色（放置后用染料右键伞方块）</li>
 * </ul>
 */
public class ParachutePackItem extends BlockItem {

    public static final String NBT_KEY = "ParachuteName";

    public ParachutePackItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    /** 读取物品 NBT 中的伞名（空 = 默认蘑菇伞） */
    public static String getParachuteName(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return data.copyTag().getString(NBT_KEY);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 右键伞包 → 先打开控制器 GUI（目标为 null = 写入手持伞包物品 NBT）。
        // 本类在专用服务端也会被加载，客户端界面必须经 ClientHooks 间接调用，见该类的说明。
        if (level.isClientSide) {
            ClientHooks.openControllerScreen(null);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (result.consumesAction() && !context.getLevel().isClientSide) {
            BlockEntity be = context.getLevel().getBlockEntity(context.getClickedPos());
            if (be instanceof ParachuteBlockEntity pbe) {
                String name = getParachuteName(context.getItemInHand());
                if (!name.isEmpty()) {
                    pbe.setParachuteName(name);
                    // 放置时的那次 setBlock 已经把「默认伞」的 NBT 发过去了，这里改名后必须再广播一次，
                    // 否则放置者自己的客户端也要等区块重载才会显示成伞包里选的伞
                    pbe.setChanged();
                    pbe.syncToClients();
                }
            }
        }
        return result;
    }
}
