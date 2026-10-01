package com.verr1.synaxis.content.blocks.controlchair;

/**
 * 编译期桩：Synaxis 的控制椅方块实体。
 *
 * <p>只用来让门控 mixin 能写出类型（{@code instanceof} / {@code CallbackInfoReturnable} 的泛型）；
 * 本源集只参与编译，桩不会进 mod jar，也不会出现在运行时 classpath 上。</p>
 */
public class ControlChairBlockEntity {
}
