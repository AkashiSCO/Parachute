package com.create.parachute.mixin;

import com.create.parachute.ParachuteMod;
import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 门控：本模组的 mixin 全部是可选的兼容补丁，只有目标 mod 在场时才应用。
 *
 * <p>目前只有一条：{@link ControlChairPilotMixin} 需要 Synaxis。没装 Synaxis 时
 * {@code shouldApplyMixin} 返回 {@code false}，Mixin 根本不会去解析那个不存在的目标类，
 * 因此不会报 "target class not found" 之类的错误。</p>
 */
public final class ParachuteMixinPlugin implements IMixinConfigPlugin {
    private static final String SYNAXIS_MOD_ID = "synaxis";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        // 目标都是 mod 类（remap = false），不需要 refmap
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = true;
        if (mixinClassName.endsWith(".ControlChairPilotMixin")) {
            apply = isModLoaded(SYNAXIS_MOD_ID) || targetClassPresent(
                    "com/verr1/synaxis/content/blocks/controlchair/ControlChairBlockEntity.class");
        } else if (mixinClassName.endsWith(".ControlChairClientSeatMixin")) {
            apply = isModLoaded(SYNAXIS_MOD_ID) || targetClassPresent(
                    "com/verr1/synaxis/foundation/input/ClientControlChairManager.class");
        } else if (mixinClassName.endsWith(".ParachuteBlockEntityNetworkMixin")
                || mixinClassName.endsWith(".ParachuteBlockChairUiMixin")) {
            // 这两个补丁给我们的方块/BE 挂上 Synaxis、ldlib2 的接口，
            // 只有目标 mod 在场时才允许应用（否则类加载会失败）
            apply = isModLoaded(SYNAXIS_MOD_ID) || targetClassPresent(
                    "com/verr1/synaxis/foundation/blockentity/NetworkBlockEntityAccess.class");
        }
        ParachuteMod.LOGGER.info("[create_parachute] mixin 门控: {} -> {}", mixinClassName, apply);
        return apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean isModLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 兜底判据：直接看目标类在不在 classpath 上。
     *
     * <p>Mixin 配置是在<b>非常早</b>的阶段准备的，那时 {@code ModList} 可能还没填好
     * （这会让 {@link #isModLoaded} 误判成 false，于是补丁被静默跳过）。用 classpath
     * 资源查在不在，就不受加载时序影响。</p>
     */
    private static boolean targetClassPresent(String resourcePath) {
        try {
            ClassLoader loader = ParachuteMixinPlugin.class.getClassLoader();
            return loader != null && loader.getResource(resourcePath) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
