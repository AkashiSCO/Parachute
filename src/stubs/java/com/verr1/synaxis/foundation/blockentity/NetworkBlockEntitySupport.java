package com.verr1.synaxis.foundation.blockentity;

/**
 * 编译期桩 —— 只为让门控 mixin 能编译。
 *
 * <p>Synaxis 的 jar 不能进仓库（{@code libs/} 被 .gitignore 忽略，CI 上也没有），
 * 所以这里提供它的<b>最小签名</b>。本源集只参与编译：
 * 桩类不会进 mod jar，也不会出现在运行时 classpath 上；运行时真实类缺失时，
 * 对应 mixin 由 {@code ParachuteMixinPlugin} 判定为不应用。</p>
 */
public class NetworkBlockEntitySupport {
}
