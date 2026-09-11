package com.create.parachute.client;

import com.create.parachute.client.assets.ParachuteAssets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端专属功能的统一入口（dist-safe 跳板）。
 *
 * <h2>为什么需要这个类</h2>
 * <p>专用服务端上根本没有 {@code net.minecraft.client.*} 这些类：NeoForge 的 runtime dist cleaner
 * 在加载它们时直接抛
 * {@code RuntimeException: Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER}。</p>
 *
 * <p>而 JVM 在<b>校验</b>一个类的字节码时就会把这些类型拉进来（校验器要判断
 * {@code new ParachuteScreen(...)} 能否赋给 {@code Minecraft.setScreen(Screen)} 的参数），
 * 所以只要方块 / 物品 / 方块实体这类<b>服务端也会加载</b>的公共类里直接出现客户端类型，
 * 服务端就会在注册阶段崩溃，跟代码走没走到那一行无关。</p>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>本类位于 {@code client} 包，只会在客户端的执行路径上被加载；</li>
 *   <li>公开方法的签名<b>只允许公共类型</b>（BlockPos / String / boolean 等），
 *       这样公共类引用本类时，JVM 校验不需要解析任何客户端类型，也不会触发本类加载。</li>
 * </ul>
 *
 * <p>因此：任何服务端也会加载的类，都必须通过本类间接使用客户端功能，
 * 而不能直接 import / new / 调用客户端类。</p>
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    /**
     * 打开降落伞控制器 GUI。
     *
     * @param targetPos 目标方块位置；{@code null} 表示手持伞包（物品没有方块参数可写）
     */
    public static void openControllerScreen(@Nullable BlockPos targetPos) {
        Minecraft.getInstance().setScreen(new ParachuteScreen(targetPos));
    }

    /**
     * 该伞是否使用基岩版（bedrock）模型格式。
     * <p>影响渲染朝向补偿：bedrock 模型 X 轴语义相反，不需要额外的 -90° 绕 Y 旋转。
     * 只有客户端持有伞的模型数据，所以服务端不参与该判断。</p>
     *
     * @param parachuteName 伞名（{@code parachute/} 下的文件夹名）
     * @return true = bedrock 格式
     */
    public static boolean isBedrockModel(String parachuteName) {
        return ParachuteAssets.isBedrock(parachuteName);
    }
}
