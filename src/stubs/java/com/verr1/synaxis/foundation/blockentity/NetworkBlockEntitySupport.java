package com.verr1.synaxis.foundation.blockentity;

import com.verr1.synaxis.foundation.command.CommandRegistry;

/**
 * 编译期桩 —— 只为让门控 mixin 能编译。
 *
 * <p>Synaxis 的 jar 不能进仓库（{@code libs/} 被 .gitignore 忽略，CI 上也没有），
 * 所以这里提供它的<b>最小签名</b>。本源集只参与编译：
 * 桩类不会进 mod jar，也不会出现在运行时 classpath 上；运行时真实类缺失时，
 * 对应 mixin 由 {@code ParachuteMixinPlugin} 判定为不应用。</p>
 */
public class NetworkBlockEntitySupport {
    /**
     * 命令表（幽灵椅子的 {@code SET_*} 命令都注册在这里）。
     *
     * <p>伞包 BE 自己转发命令时要用它：Synaxis 的 {@code handleCommand} 里那道
     * {@code canPlayerUse} 校验对不在 level BE 表里的幽灵椅子不成立，所以改成
     * 由伞包校验玩家、再把命令交给这张表执行。</p>
     */
    public CommandRegistry commands() {
        return null;
    }
}
