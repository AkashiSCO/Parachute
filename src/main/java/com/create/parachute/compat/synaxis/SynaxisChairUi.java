package com.create.parachute.compat.synaxis;

import com.create.parachute.ParachuteMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * 客户端侧：打开 Synaxis 控制椅的「绑定」配置界面。
 *
 * <h3>为什么不能走服务器</h3>
 * <p>Synaxis 椅子自己的 Shift+右键走的是
 * {@code NetworkBlockEntitySupport#openSettings(player)}，而它的实现是
 * {@code BlockUIMenuType.openUI(player, owner.getBlockPos())} —— ldlib2 会<b>按坐标</b>去
 * {@code level.getBlockEntity(pos)} 找 UI 宿主。那个坐标上的 BE 是我们的伞包 BE，
 * 所以幽灵椅子再真也顶不了这个位置，调用必然返回 false。</p>
 *
 * <p>但椅子的「绑定」界面不一样：它是一个<b>普通客户端 Screen</b>
 * （{@code ControlChairBindingsScreen extends Screen}，无参构造），
 * Synaxis 自己也是客户端直接 {@code Minecraft.setScreen(new ...)} 打开的
 * （见 {@code ClientControlChairManager#showBindingsDeferredMessage()}）。
 * 所以这里照做即可，不需要 ldlib、不需要按坐标查 BE。</p>
 *
 * <p>全部走反射：本模组对 Synaxis 没有编译期依赖，没装 Synaxis 时 {@link SynaxisCompat#isLoaded()}
 * 直接短路。</p>
 */
public final class SynaxisChairUi {
    private static final String CLIENT_MANAGER =
            "com.verr1.synaxis.foundation.input.ClientControlChairManager";
    private static final String BINDINGS_SCREEN =
            "com.verr1.synaxis.content.gui.screens.ControlChairBindingsScreen";

    /** 打开控制椅绑定界面（只在客户端调用）。 */
    public static void openBindingsScreen() {
        if (!SynaxisCompat.isLoaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            // 已经有界面开着（比如刚打开别的 GUI）就别抢，免得把玩家的界面顶掉
            return;
        }
        // 优先用 Synaxis 自己的入口：它会顺带 resetAllInputStates + reloadFromDisk
        try {
            Class<?> manager = Class.forName(CLIENT_MANAGER);
            Method show = manager.getDeclaredMethod("showBindingsDeferredMessage");
            show.setAccessible(true);
            show.invoke(null);
            ParachuteMod.LOGGER.info("[create_parachute] 已打开 Synaxis 控制椅绑定界面（走 ClientControlChairManager）");
            return;
        } catch (ClassNotFoundException e) {
            ParachuteMod.LOGGER.info("[create_parachute] 没找到 Synaxis 控制椅界面类（Synaxis 版本不同？）");
            return;
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn("[create_parachute] 调 Synaxis 绑定界面入口失败，改用直接构造：{}", t.toString());
        }

        // 兜底：直接 new 出 Screen 自己 set
        try {
            Class<?> screenClass = Class.forName(BINDINGS_SCREEN);
            Object screen = screenClass.getConstructor().newInstance();
            minecraft.setScreen((Screen) screen);
            ParachuteMod.LOGGER.info("[create_parachute] 已打开 Synaxis 控制椅绑定界面（直接构造 Screen）");
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn("[create_parachute] 打开 Synaxis 控制椅绑定界面失败：{}", t.toString());
        }
    }

    private SynaxisChairUi() {
    }
}
