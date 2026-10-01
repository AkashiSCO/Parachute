package com.verr1.synaxis.foundation.command;

import com.verr1.synaxis.foundation.network.BlockCommandPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 编译期桩：Synaxis 的命令表（{@code NetworkBlockEntitySupport#commands()}）。
 *
 * <p>只列出我们真正调用的方法，签名与 Synaxis 1.5.0 一致，
 * 因此编译出来的 mixin 字节码在运行时能对上真类。</p>
 */
public class CommandRegistry {
    /** 执行一条从客户端发来的命令（服务端调用）。 */
    public void execute(BlockCommandPayload payload, ServerPlayer player) {
    }
}
