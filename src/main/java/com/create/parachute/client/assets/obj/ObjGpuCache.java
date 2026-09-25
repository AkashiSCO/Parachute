package com.create.parachute.client.assets.obj;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一层 OBJ 网格的 GPU 缓冲缓存：按 (光照, 染色) 组合各烘一份。
 *
 * <h2>为什么按光照+染色分组</h2>
 * <p>MC 的实体着色器按逐顶点 UV2 采样光照贴图，而染色/不透明度也是逐顶点颜色 ——
 * 这些值都烘死在缓冲里，所以每个组合要单独一份；命中缓存时每帧只 bind + draw。</p>
 *
 * <p><b>光照量化</b>：按 {@link #LIGHT_QUANTUM} 级归并。白天(15)和夜里(4)光照稳定，
 * 一直命中同一个缓冲、永不重烘；只有日出日落那种渐变才会重烘几次，
 * 而量化带来的亮度差小于 2%，肉眼看不出来。</p>
 *
 * <p>缓存上限 {@link #MAX_VARIANTS}，超出后按插入顺序淘汰最旧的（并释放 GL 缓冲）。</p>
 */
public final class ObjGpuCache implements AutoCloseable {

    /** 光照量化步长（16 位光照值，步长 4 ≈ 亮度差 1.5%，肉眼无感） */
    private static final int LIGHT_QUANTUM = 4;
    /** 每个层最多缓存的 (光照, 染色) 组合数（够覆盖白天/夜里/染色，同时限制显存） */
    private static final int MAX_VARIANTS = 2;

    private final List<ObjMesh.Group> groups;
    private final Map<Long, ObjGpuMesh> variants = new LinkedHashMap<>();
    private final int triangles;

    public ObjGpuCache(List<ObjMesh.Group> groups) {
        this.groups = new ArrayList<>(groups);
        int count = 0;
        for (ObjMesh.Group group : groups) {
            count += group.cornerCount() / 3;
        }
        this.triangles = count;
    }

    public int triangles() {
        return this.triangles;
    }

    /**
     * 取（必要时构建）这一层在给定光照/染色下的 GPU 缓冲；必须在渲染线程调用。
     * <p>
     * packedLight 是 32 位的：低 16 位=方块光，高 16 位=天空光（白天全亮是 0xF000F0）。
     * 两半必须分别量化——曾经整体 clamp 到 0xFFFF，把 0xF000F0 压成 0xFFFF，
     * 顶点着色器里 texelFetch(Sampler2, UV2/16) 坐标越界返回全 0，
     * 表现为模型全黑且镂空（光照贴图 alpha=0 被 cutout 丢弃）。
     */
    public ObjGpuMesh get(int packedLight, int tintColor) {
        int block = (packedLight & 0xFFFF) / LIGHT_QUANTUM * LIGHT_QUANTUM;
        int sky = ((packedLight >>> 16) & 0xFFFF) / LIGHT_QUANTUM * LIGHT_QUANTUM;
        int quantized = block | (sky << 16);
        long key = (((long) block) << 48) | (((long) sky) << 32) | (tintColor & 0xFFFFFFFFL);
        ObjGpuMesh mesh = this.variants.get(key);
        if (mesh != null) {
            return mesh;
        }
        mesh = ObjGpuMesh.bake(this.groups, quantized, tintColor);
        if (this.variants.size() >= MAX_VARIANTS) {
            Iterator<Map.Entry<Long, ObjGpuMesh>> it = this.variants.entrySet().iterator();
            if (it.hasNext()) {
                it.next().getValue().close();
                it.remove();
            }
        }
        this.variants.put(key, mesh);
        return mesh;
    }

    /** 释放所有 GL 缓冲（热重载/删除伞时调用；必须在渲染线程） */
    @Override
    public void close() {
        for (ObjGpuMesh mesh : this.variants.values()) {
            mesh.close();
        }
        this.variants.clear();
    }
}
