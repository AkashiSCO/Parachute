package com.create.parachute.client;

/**
 * 光影（Iris）兼容判断。
 *
 * <p>本模组的 GPU 烘焙路径用的是自己注册的 core shader（{@code create_parachute:obj_entity_*}）。
 * Iris 只接管<b>原版</b> shader 的绘制，自定义 {@code ShaderInstance} 不在它的管线里 ——
 * 一开光影包，那条路径就什么都画不出来（模型直接消失）。</p>
 *
 * <p>所以开启光影时改用原版实体着色器实例（{@code GameRenderer.getRendertypeEntityCutoutNoCullShader()}），
 * Iris 会把它替换成自己的程序，绘制就能正常走光影管线。用反射探测，避免对 Iris 产生硬依赖。</p>
 *
 * <p>这个判断还决定<b>用哪个着色器实例</b>（自有 / 原版+光影包），而几何份数与剔除由
 * {@code ParachuteConfig.ShadersGeometry} 统一决定（开不开光影都一样，见 {@code ObjMeshCube.Backface}）。
 * 渲染器每帧只探测一次并把结果传下去，保证"烘的时候"和"画的时候"用的是同一个值。</p>
 */
public final class ShaderPackCompat {

    /** 反射结果缓存时长：光影包开关随时可能变，1 秒重新探测一次足够 */
    private static final long CACHE_MS = 1000L;

    private static boolean cached;
    private static long cachedAt;

    private ShaderPackCompat() {
    }

    /** 当前是否有光影包在生效（没装 Iris 时恒为 false） */
    public static boolean shaderPackInUse() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) {
            return cached;
        }
        cachedAt = now;
        cached = detect();
        return cached;
    }

    private static boolean detect() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object inUse = api.getMethod("isShaderPackInUse").invoke(instance);
            return inUse instanceof Boolean b && b;
        } catch (Throwable ignored) {
            // 没装 Iris / API 变了 / 模块不可访问 —— 一律当作没有光影
            return false;
        }
    }
}
