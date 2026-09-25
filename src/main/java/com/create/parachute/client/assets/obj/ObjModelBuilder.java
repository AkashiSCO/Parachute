package com.create.parachute.client.assets.obj;

import net.minecraft.client.model.geom.ModelPart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把烘焙好的 {@link ObjMesh} 装成一棵 {@link ModelPart} 树，交给现有的 {@code ParachuteRenderer} 渲染。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>每个组（{@code o}，没有 {@code o} 时用 {@code g}）→ 一个子 ModelPart（= 骨骼名），
 *       几何用绝对坐标，<b>部件姿态恒为单位</b>。这样模型位置完全由 OBJ 坐标决定，
 *       摆放/缩放交给方块实体那套（GUI 的 M/P/R/Scale）即可。</li>
 *   <li>组的名字直接当骨骼名，所以将来想让 OBJ 复用同名 {@code .bbmodel} 的动画，
 *       只要两边名字对齐就行（本次没做动画）。</li>
 *   <li>没有名字的几何（文件里 {@code o}/{@code g} 都没有，或第一个 {@code o} 之前的面）
 *       直接挂在 root 上。</li>
 * </ul>
 *
 * <p>朝向：OBJ（Blender 默认导出 forward=-Z / up=Y）与 Blockbench 的 modded_entity 同属
 * "X 不镜像"的一类，所以走渲染器里既有的那条分支（会额外绕 Y 转 -90°）。
 * 如果实测发现转了 90°/180°，改这里或 {@code ParachuteBlockEntity} 里的补偿即可 ——
 * 建议先用一张带 RGB 轴标记的测试模型确认。</p>
 */
public final class ObjModelBuilder {

    private ObjModelBuilder() {
    }

    public static ModelPart build(ObjMesh mesh) {
        return build(mesh.groups());
    }

    /**
     * 把若干组装成一棵 ModelPart 树。
     *
     * <p>多材质模型会先按贴图把组合并成"层"，每一层调用一次本方法；单贴图模型只有一层，
     * 结构和以前完全一致（每个 {@code o} 对象一个命名子节点）。</p>
     */
    public static ModelPart build(List<ObjMesh.Group> groups) {
        List<ModelPart.Cube> rootCubes = new ArrayList<>();
        Map<String, ModelPart> children = new LinkedHashMap<>();
        Map<String, Integer> used = new HashMap<>();

        for (ObjMesh.Group group : groups) {
            if (group.cornerCount() <= 0) continue;
            ObjMeshCube cube = new ObjMeshCube(group);
            String name = group.name();
            if (name == null || name.isEmpty()) {
                rootCubes.add(cube);
                continue;
            }
            children.put(unique(name, used), new ModelPart(List.<ModelPart.Cube>of(cube), Map.of()));
        }
        return new ModelPart(rootCubes, children);
    }

    /** 同名组去重（OBJ 里不同对象可以重名） */
    private static String unique(String name, Map<String, Integer> used) {
        Integer n = used.get(name);
        if (n == null) {
            used.put(name, 1);
            return name;
        }
        used.put(name, n + 1);
        return name + "_" + n;
    }
}
