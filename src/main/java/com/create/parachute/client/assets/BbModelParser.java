package com.create.parachute.client.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 运行时 .bbmodel → MC {@link LayerDefinition} 解析器。
 *
 * <p>转换规则（由 BlockBench 编辑器坐标 → MC 实体模型空间的 Y 反射相似变换推导，
 * 与已生成模型的同步子集逐一比对验证）：</p>
 * <ul>
 *   <li><b>无旋转元素</b>：{@code addBox(from.x, -to.y, from.z, sx, sy, sz)}，PartPose.offset(0,0,0)</li>
 *   <li><b>有旋转元素</b>：盒相对 origin（X/Z 不翻转、Y 翻转：from.x-org.x, org.y-to.y, from.z-org.z），
 *       pose = (org.x, -org.y, org.z)，旋转 = (-rx, +ry, -rz) 角度转弧度</li>
 *   <li>层级来自 outliner 树；组的名字取 animators 里的骨名（动画按骨名绑定）</li>
 *   <li>组偏移 = 本组 origin 相对父组的差值 (dx,dy,dz)，pose = (dx, -dy, dz)（X/Z 不翻转、Y 翻转）</li>
 *   <li>UV 取元素 {@code uv_offset}（box_uv 布局），与导出一致</li>
 * </ul>
 */
public final class BbModelParser {

    private BbModelParser() {
    }

    /**
     * 解析 .bbmodel JSON 为 LayerDefinition。
     *
     * @param reader .bbmodel 文件内容
     * @return 可用于 {@code bake()} 的 LayerDefinition；解析失败返回 null（调用方回退蘑菇伞）
     */
    @Nullable
    public static LayerDefinition parse(Reader reader) {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return parse(root);
        } catch (Exception e) {
            com.create.parachute.ExampleMod.LOGGER.warn("Failed to parse bbmodel model: {}", e.toString());
            return null;
        }
    }

    @Nullable
    public static LayerDefinition parse(JsonObject root) {
        try {
            int texW = GsonHelper.getAsInt(GsonHelper.getAsJsonObject(root, "resolution"), "width", 64);
            int texH = GsonHelper.getAsInt(GsonHelper.getAsJsonObject(root, "resolution"), "height", 64);

            Map<String, JsonObject> elements = new HashMap<>();
            for (JsonElement e : GsonHelper.getAsJsonArray(root, "elements", new JsonArray())) {
                JsonObject el = e.getAsJsonObject();
                String uuid = GsonHelper.getAsString(el, "uuid", "");
                if (!uuid.isEmpty()) elements.put(uuid, el);
            }

            // groups[]: uuid -> {name, origin, rotation}
            Map<String, JsonObject> groups = new HashMap<>();
            for (JsonElement g : GsonHelper.getAsJsonArray(root, "groups", new JsonArray())) {
                JsonObject go = g.getAsJsonObject();
                String uuid = GsonHelper.getAsString(go, "uuid", "");
                if (!uuid.isEmpty()) groups.put(uuid, go);
            }

            // animations[0].animators: uuid -> {name, type}（骨名 = 组件的 part 名）
            Map<String, String> animBoneNames = new HashMap<>();
            JsonArray anims = GsonHelper.getAsJsonArray(root, "animations", new JsonArray());
            if (!anims.isEmpty()) {
                JsonObject anim = anims.get(0).getAsJsonObject();
                JsonObject animators = GsonHelper.getAsJsonObject(anim, "animators", new JsonObject());
                for (Map.Entry<String, JsonElement> en : animators.entrySet()) {
                    if (en.getValue().isJsonObject()) {
                        JsonObject b = en.getValue().getAsJsonObject();
                        if ("bone".equals(GsonHelper.getAsString(b, "type", ""))) {
                            animBoneNames.put(en.getKey(), GsonHelper.getAsString(b, "name", ""));
                        }
                    }
                }
            }

            MeshDefinition mesh = new MeshDefinition();
            PartDefinition rootPart = mesh.getRoot().addOrReplaceChild(
                    "root", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));

            JsonArray outliner = GsonHelper.getAsJsonArray(root, "outliner", new JsonArray());
            buildNodes(outliner, rootPart, elements, groups, animBoneNames,
                    new float[]{0.0F, 0.0F, 0.0F}, new float[]{0.0F, 0.0F, 0.0F}, true);

            return LayerDefinition.create(mesh, texW, texH);
        } catch (Exception e) {
            com.create.parachute.ExampleMod.LOGGER.warn("Failed to build bbmodel layer: {}", e.toString());
            return null;
        }
    }

    /**
     * 递归构建 part 树。
     *
     * @param accOrg  当前组的<strong>世界</strong> origin（bbmodel 组 origin 是绝对世界坐标；
     *                元素盒相对它计算，父链上的组偏移是"相对父组"的差值，不能累加）
     * @param parentOrigin 父组的 world origin（用于计算本组相对父组的偏移）
     * @param topLevel 顶层（root）节点：姿态强制归零（对应工作流"导出后 root 坐标手动改 0,0,0"）
     */
    private static void buildNodes(JsonArray nodes, PartDefinition parent,
                                   Map<String, JsonObject> elements, Map<String, JsonObject> groups,
                                   Map<String, String> animBoneNames,
                                   float[] accOrg, float[] parentOrigin, boolean topLevel) {
        int cubeIndex = 0;
        for (JsonElement nodeEl : nodes) {
            if (nodeEl.isJsonPrimitive()) {
                // 叶子：元素 uuid
                JsonObject elem = elements.get(nodeEl.getAsString());
                if (elem != null && isExported(elem)) {
                    parent.addOrReplaceChild(uniqueCubeName(elem, cubeIndex++),
                            cubeBuilder(elem, accOrg), elementPose(elem, accOrg));
                }
                continue;
            }
            JsonObject node = nodeEl.getAsJsonObject();
            String uuid = GsonHelper.getAsString(node, "uuid", "");
            JsonObject groupDef = groups.get(uuid);
            String name = animBoneNames.getOrDefault(uuid, groupDef != null ? GsonHelper.getAsString(groupDef, "name", "") : "");
            if (name.isEmpty()) name = sanitize("bone_" + uuid);

            if (topLevel) {
                // 顶层 root：忽略其 origin/rotation（用户工作流：导出后 root 坐标手动改 0,0,0）
                PartDefinition part = parent.addOrReplaceChild(name, CubeListBuilder.create(), PartPose.ZERO);
                buildNodes(GsonHelper.getAsJsonArray(node, "children", new JsonArray()),
                        part, elements, groups, animBoneNames, accOrg, accOrg, false);
                continue;
            }

            float[] origin = groupDef != null
                    ? floats(groupDef, "origin", new float[]{0, 0, 0})
                    : new float[]{0, 0, 0};
            // 本组偏移 = 本组 world origin - 父组 world origin（X/Z 不翻转、Y 翻转）
            float dx = origin[0] - parentOrigin[0];
            float dy = origin[1] - parentOrigin[1];
            float dz = origin[2] - parentOrigin[2];
            PartPose pose = groupPose(groupDef, dx, dy, dz);
            PartDefinition part = parent.addOrReplaceChild(name, CubeListBuilder.create(), pose);
            // 子元素的盒子相对本组 world origin（设置，不累加）
            buildNodes(GsonHelper.getAsJsonArray(node, "children", new JsonArray()),
                    part, elements, groups, animBoneNames, origin, origin, false);
        }
    }

    /** 元素是否有立方体盒（只导出 cube 类型且 export=true） */
    private static boolean isExported(JsonObject elem) {
        if ("cube".equals(GsonHelper.getAsString(elem, "type", "cube"))) {
            return GsonHelper.getAsBoolean(elem, "export", true);
        }
        return false;
    }

    private static CubeListBuilder cubeBuilder(JsonObject elem, float[] accOrg) {
        float[] fr = floats(elem, "from", new float[]{0, 0, 0});
        float[] to = floats(elem, "to", new float[]{0, 0, 0});
        float sx = to[0] - fr[0];
        float sy = to[1] - fr[1];
        float sz = to[2] - fr[2];
        // 相对累积组原点（BlockBench 元素坐标是绝对坐标，组 origin 只是旋转轴心）
        float rfx = fr[0] - accOrg[0];
        float rfy = fr[1] - accOrg[1];
        float rfz = fr[2] - accOrg[2];
        float rtx = to[0] - accOrg[0];
        float rty = to[1] - accOrg[1];

        JsonArray uvArr = GsonHelper.getAsJsonArray(elem, "uv_offset", new JsonArray());
        int u = uvArr.size() >= 2 ? (int) uvArr.get(0).getAsFloat() : 0;
        int v = uvArr.size() >= 2 ? (int) uvArr.get(1).getAsFloat() : 0;

        float[] rot = rotationOf(elem);
        float bx, by, bz;
        if (rot != null) {
            // 有旋转：盒相对元素自身 origin（X 翻转、Y 翻转），与 BlockBench 导出一致
            float[] org = originOf(elem, fr, to);
            bx = -(rtx - (org[0] - accOrg[0]));
            by = -(rty - (org[1] - accOrg[1]));
            bz = rfz - (org[2] - accOrg[2]);
        } else {
            bx = rfx;
            by = -rty;
            bz = rfz;
        }
        return CubeListBuilder.create().texOffs(u, v)
                .addBox(bx, by, bz, sx, sy, sz, new CubeDeformation(0.0F));
    }

    private static PartPose elementPose(JsonObject elem, float[] accOrg) {
        float[] fr = floats(elem, "from", new float[]{0, 0, 0});
        float[] to = floats(elem, "to", new float[]{0, 0, 0});
        float[] rot = rotationOf(elem);
        if (rot == null) {
            return PartPose.offset(0.0F, 0.0F, 0.0F);
        }
        float[] org = originOf(elem, fr, to);
        float rox = org[0] - accOrg[0];
        float roy = org[1] - accOrg[1];
        float roz = org[2] - accOrg[2];
        return PartPose.offsetAndRotation(-rox, -roy, roz,
                (float) Math.toRadians(-rot[0]), (float) Math.toRadians(-rot[1]), (float) Math.toRadians(rot[2]));
    }

    /** 组姿态：偏移 = 相对父组的差值 (dx,dy,dz) 翻转（X 翻转、Y 翻转），叠加组旋转（X 翻转、Y 翻转） */
    private static PartPose groupPose(@Nullable JsonObject groupDef, float dx, float dy, float dz) {
        float[] rot = groupDef != null ? rotationOf(groupDef) : null;
        if (rot == null) {
            return PartPose.offset(-dx, -dy, dz);
        }
        return PartPose.offsetAndRotation(-dx, -dy, dz,
                (float) Math.toRadians(-rot[0]), (float) Math.toRadians(-rot[1]), (float) Math.toRadians(rot[2]));
    }

    /** 元素旋转；无 rotation 字段或全 0 时返回 null（视为无旋转） */
    @Nullable
    private static float[] rotationOf(JsonObject obj) {
        JsonArray arr = GsonHelper.getAsJsonArray(obj, "rotation", new JsonArray());
        if (arr.size() < 3) return null;
        float[] r = new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat()};
        if (Math.abs(r[0]) < 1.0E-4F && Math.abs(r[1]) < 1.0E-4F && Math.abs(r[2]) < 1.0E-4F) {
            return null;
        }
        return r;
    }

    /** origin；缺省为盒中心（BlockBench 默认行为） */
    private static float[] originOf(JsonObject elem, float[] fr, float[] to) {
        float[] org = floats(elem, "origin", new float[]{0, 0, 0});
        if (!elem.has("origin")) {
            org[0] = (fr[0] + to[0]) / 2.0F;
            org[1] = (fr[1] + to[1]) / 2.0F;
            org[2] = (fr[2] + to[2]) / 2.0F;
        }
        return org;
    }

    private static float[] floats(JsonObject obj, String key, float[] def) {
        JsonArray arr = GsonHelper.getAsJsonArray(obj, key, new JsonArray());
        if (arr.size() < 3) return def;
        return new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat()};
    }

    private static String uniqueCubeName(JsonObject elem, int index) {
        String base = sanitize(GsonHelper.getAsString(elem, "name", ""));
        if (base.isEmpty()) base = "cube";
        String uuid = GsonHelper.getAsString(elem, "uuid", "");
        String suffix = uuid.length() >= 4 ? uuid.substring(0, 4) : Integer.toHexString(index);
        return base + "_" + suffix;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        if (out.isEmpty()) out = "bone";
        if (Character.isDigit(out.charAt(0))) out = "b_" + out;
        return out.toLowerCase(Locale.ROOT);
    }

    // ============================================================
    // 基岩版 .bbmodel（box_uv=false 逐面 UV）支持：直接构建 ModelPart
    // ============================================================

    /**
     * 方块是否用逐面 UV：仅 box_uv=false 的元素才是真正的逐面 UV（基岩版 lav25 之类）。
     * box_uv=true 的元素（即使 uv_offset 为空）按 BlockBench 导出一律用标准箱型 UV
     * （texOffs）。此前把"box_uv=true 且 uv_offset 空"误判为逐面，导致伞模型的
     * rim 大板子走 CustomUvCube、并把整个模型踢进直接构建路径，渲染错乱。
     */
    private static boolean isPerFaceCube(JsonObject elem) {
        if (!elem.has("faces")) return false;
        return !GsonHelper.getAsBoolean(elem, "box_uv", true);
    }

    /**
     * 模型里是否有需要逐面 UV 的方块——需要走直接 ModelPart 构建路径；
     * 全箱型 UV 的模型仍走原 LayerDefinition 路径。
     */
    public static boolean hasPerFaceUv(JsonObject root) {
        for (JsonElement e : GsonHelper.getAsJsonArray(root, "elements", new JsonArray())) {
            if (e.isJsonObject() && isPerFaceCube(e.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 直接构建 ModelPart 树（不经过 LayerDefinition），支持逐面 UV 与普通 box_uv 混合模型。
     */
    @Nullable
    public static ModelPart parseModelPart(JsonObject root) {
        try {
            int texW = GsonHelper.getAsInt(GsonHelper.getAsJsonObject(root, "resolution"), "width", 64);
            int texH = GsonHelper.getAsInt(GsonHelper.getAsJsonObject(root, "resolution"), "height", 64);
            // Bedrock 模式 .bbmodel 的 X 轴语义与 modded_entity 相反：编辑器里 +X 为右，
            // 但渲染到 MC 实体模型（-Z 朝前）时 +X 为左，故 bedrock 模型需用 X 不翻转的 fullg 约定，
            // 而 modded_entity 用 BlockBench 导出的 cur 约定（X 翻转）。
            Map<String, JsonObject> elements = new HashMap<>();
            for (JsonElement e : GsonHelper.getAsJsonArray(root, "elements", new JsonArray())) {
                JsonObject el = e.getAsJsonObject();
                String uuid = GsonHelper.getAsString(el, "uuid", "");
                if (!uuid.isEmpty()) elements.put(uuid, el);
            }
            Map<String, JsonObject> groups = new HashMap<>();
            for (JsonElement g : GsonHelper.getAsJsonArray(root, "groups", new JsonArray())) {
                JsonObject go = g.getAsJsonObject();
                String uuid = GsonHelper.getAsString(go, "uuid", "");
                if (!uuid.isEmpty()) groups.put(uuid, go);
            }
            Map<String, String> animBoneNames = new HashMap<>();
            JsonArray anims = GsonHelper.getAsJsonArray(root, "animations", new JsonArray());
            if (!anims.isEmpty()) {
                JsonObject anim = anims.get(0).getAsJsonObject();
                JsonObject animators = GsonHelper.getAsJsonObject(anim, "animators", new JsonObject());
                for (Map.Entry<String, JsonElement> en : animators.entrySet()) {
                    if (en.getValue().isJsonObject()) {
                        JsonObject b = en.getValue().getAsJsonObject();
                        if ("bone".equals(GsonHelper.getAsString(b, "type", ""))) {
                            animBoneNames.put(en.getKey(), GsonHelper.getAsString(b, "name", ""));
                        }
                    }
                }
            }

            JsonArray outliner = GsonHelper.getAsJsonArray(root, "outliner", new JsonArray());
            return buildModelParts(outliner, elements, groups, animBoneNames,
                    new float[]{0.0F, 0.0F, 0.0F}, new float[]{0.0F, 0.0F, 0.0F}, true, texW, texH);
        } catch (Exception e) {
            com.create.parachute.ExampleMod.LOGGER.warn("Failed to build bbmodel ModelPart: {}", e.toString());
            return null;
        }
    }

    /** 读取 meta.model_format；缺省视为 modded_entity */
    private static String modelFormat(JsonObject root) {
        JsonObject meta = GsonHelper.getAsJsonObject(root, "meta", new JsonObject());
        return GsonHelper.getAsString(meta, "model_format", "modded_entity");
    }

    private static ModelPart buildModelParts(JsonArray nodes,
                                             Map<String, JsonObject> elements, Map<String, JsonObject> groups,
                                             Map<String, String> animBoneNames,
                                             float[] accOrg, float[] parentOrigin, boolean topLevel, int texW, int texH) {
        java.util.List<ModelPart.Cube> cubes = new java.util.ArrayList<>();
        Map<String, ModelPart> children = new HashMap<>();
        int cubeIndex = 0;
        for (JsonElement nodeEl : nodes) {
            if (nodeEl.isJsonPrimitive()) {
                JsonObject elem = elements.get(nodeEl.getAsString());
                if (elem != null && isExported(elem)) {
                    ModelPart.Cube c = buildCubeDirect(elem, accOrg, texW, texH, false);
                    if (c != null) {
                        // 每个叶子方块包一层带姿态的 part。
                        // modded_entity(伞)：cur 公式 pose=(-org.x,-org.y,+org.z)，rot=(-rx,-ry,+rz)
                        //   （已由 ParachuteModel.java 验证）
                        // bedrock(lav25)：物理推导（QuatTest2 证明渲染 rotateTo 反平行时 = 绕X轴180°：
                        //   X不变、Y翻、Z翻）。为补偿：
                        //   - X 不翻（否则 rotateTo 不动 X，左轮被 cur X翻到右 → 镜像）
                        //   - Y 翻（同伞）
                        //   - Z 翻（补偿 rotateTo 把车头 -Z 翻成 +Z）
                        //   pose=(org.x,-org.y,-org.z)，rot=(-rx,-ry,+rz)（LavSim20 全模型验证）
                        ModelPart cubePart = new ModelPart(java.util.List.of(c), Map.of());
                        float[] rot = rotationOf(elem);
                        if (rot != null) {
                            float[] fr = floats(elem, "from", new float[]{0, 0, 0});
                            float[] to = floats(elem, "to", new float[]{0, 0, 0});
                            float[] org = originOf(elem, fr, to);
                            float rox = org[0] - accOrg[0];
                            float roy = org[1] - accOrg[1];
                            float roz = org[2] - accOrg[2];
                            cubePart.setPos(-rox, -roy, roz);
                            cubePart.setRotation((float) Math.toRadians(-rot[0]),
                                    (float) Math.toRadians(-rot[1]), (float) Math.toRadians(rot[2]));
                        }
                        children.put(uniqueCubeName(elem, cubeIndex++), cubePart);
                    }
                }
                continue;
            }
            JsonObject node = nodeEl.getAsJsonObject();
            String uuid = GsonHelper.getAsString(node, "uuid", "");
            JsonObject groupDef = groups.get(uuid);
            String name = animBoneNames.getOrDefault(uuid,
                    groupDef != null ? GsonHelper.getAsString(groupDef, "name", "") : "");
            if (name.isEmpty()) name = sanitize("bone_" + uuid);

            if (topLevel) {
                // 顶层 root 组：零姿态
                ModelPart part = buildModelParts(GsonHelper.getAsJsonArray(node, "children", new JsonArray()),
                        elements, groups, animBoneNames, accOrg, accOrg, false, texW, texH);
                children.put(name, part);
                continue;
            }
            float[] origin = groupDef != null
                    ? floats(groupDef, "origin", new float[]{0, 0, 0})
                    : new float[]{0, 0, 0};
            float dx = origin[0] - parentOrigin[0];
            float dy = origin[1] - parentOrigin[1];
            float dz = origin[2] - parentOrigin[2];
            ModelPart part = buildModelParts(GsonHelper.getAsJsonArray(node, "children", new JsonArray()),
                    elements, groups, animBoneNames, origin, origin, false, texW, texH);
            // 组偏移 = 相对父组差值。
            // modded_entity(伞)：组 origin 全为 0，偏移恒 0，cur (-dx,-dy,+dz) 无影响
            // bedrock(lav25)：组 origin 是绝对世界坐标（左轮 -21.6 / 右轮 +21.6），
            //   X 不翻转 + Z 翻转 (dx,-dy,-dz)：L1 留左、R1 留右，车头保持 -Z
            //   （LavSim20 物理推导 + 全模型模拟验证）
            part.setPos(-dx, -dy, dz);
            float[] rot = groupDef != null ? rotationOf(groupDef) : null;
            if (rot != null) {
                part.setRotation((float) Math.toRadians(-rot[0]), (float) Math.toRadians(-rot[1]), (float) Math.toRadians(rot[2]));
            }
            children.put(name, part);
        }
        return new ModelPart(cubes, children);
    }

    /** 直接构造单个方块：逐面 UV（box_uv=false 带 faces）用 CustomUvCube，否则用原版 Cube */
    @Nullable
    private static ModelPart.Cube buildCubeDirect(JsonObject elem, float[] accOrg, int texW, int texH, boolean bedrock) {
        float[] fr = floats(elem, "from", new float[]{0, 0, 0});
        float[] to = floats(elem, "to", new float[]{0, 0, 0});
        float sx = to[0] - fr[0];
        float sy = to[1] - fr[1];
        float sz = to[2] - fr[2];
        float rfx = fr[0] - accOrg[0];
        float rfy = fr[1] - accOrg[1];
        float rfz = fr[2] - accOrg[2];
        float rtx = to[0] - accOrg[0];
        float rty = to[1] - accOrg[1];

        JsonArray uvArr = GsonHelper.getAsJsonArray(elem, "uv_offset", new JsonArray());
        int u = uvArr.size() >= 2 ? (int) uvArr.get(0).getAsFloat() : 0;
        int v = uvArr.size() >= 2 ? (int) uvArr.get(1).getAsFloat() : 0;

        float[] rot = rotationOf(elem);
        float bx, by, bz;
        if (rot != null) {
            float[] org = originOf(elem, fr, to);
            if (bedrock) {
                // lav25：X 不翻 (from.x-org.x)、Y 翻、Z 翻（LavSim20 验证）
                bx = rfx - (org[0] - accOrg[0]);
                by = -(rty - (org[1] - accOrg[1]));
                bz = -(rfz - (org[2] - accOrg[2]));
            } else {
                // 伞 cur 盒：X 翻转 -(to.x-org.x)、Y 翻转、Z 不翻转
                bx = -(rtx - (org[0] - accOrg[0]));
                by = -(rty - (org[1] - accOrg[1]));
                bz = rfz - (org[2] - accOrg[2]);
            }
        } else if (bedrock) {
            bx = rfx;
            by = -rty;
            bz = -rfz;
        } else {
            // 无旋转元素：盒 X 也翻转 -(to.x-acc.x)（基岩版 X 镜像约定，与旋转元素统一到 -C.x），
            // 否则非零组原点下无旋转元素相对旋转元素产生 X 偏移（如轮毂 vs 轮辐）。
            bx = -rtx;
            by = -rty;
            bz = rfz;
        }

        boolean perFace = isPerFaceCube(elem);
        if (perFace) {
            float[] u0 = new float[6], v0 = new float[6], u1 = new float[6], v1 = new float[6];
            boolean[] valid = new boolean[6];
            parseFaces(elem.getAsJsonObject("faces"), u0, v0, u1, v1, valid);
            return new CustomUvCube(u, v, bx, by, bz, sx, sy, sz, 0.0F, 0.0F, 0.0F,
                    false, texW, texH, u0, v0, u1, v1, valid);
        }
        return new ModelPart.Cube(u, v, bx, by, bz, sx, sy, sz, 0.0F, 0.0F, 0.0F,
                false, texW, texH, java.util.EnumSet.allOf(net.minecraft.core.Direction.class));
    }

    /**
     * 解析 faces 逐面 UV 矩形 [uMin,vMin,uMax,vMax]，面顺序 DOWN UP WEST NORTH EAST SOUTH。
     *
     * 物理映射（游戏盒 X/Y 相对 authored 翻转、Z 不翻转，LavSim40/46 世界角验证）：
     *   game DOWN ↔ authored up, UP ↔ down, WEST ↔ east, NORTH ↔ north,
     *   EAST ↔ west, SOUTH ↔ south
     * 故矩形分配固定为 {up, down, east, north, west, south}。
     *
     * mirror_uv 对逐面（box_uv=false）方块无效果：BlockBench 自己的逐面渲染器
     * （bb_bundle.js updateUV 非 box_uv 分支）完全忽略 mirror_uv，只有 box_uv 分支
     * 才处理它。镜像建模（createClone）翻转的是几何 from/to，UV 矩形按原样保存，
     * 所以这里对所有方块一律不做 west/east 或 up/down 之外的补偿。
     */
    private static void parseFaces(JsonObject faces, float[] u0, float[] v0, float[] u1, float[] v1, boolean[] valid) {
        String[] names = new String[]{"up", "down", "east", "north", "west", "south"};
        for (int i = 0; i < 6; i++) {
            if (!faces.has(names[i])) continue;
            JsonObject face = faces.getAsJsonObject(names[i]);
            // BlockBench 基岩版：texture 为 null 的面未贴图（uv 只是 [0,0,0,0] 占位），
            // 不采样 (0,0)（那里是透明/暗区，会把面渲染成黑斑），直接跳过该面。
            if (!face.has("texture") || face.get("texture").isJsonNull()) continue;
            JsonArray uv = GsonHelper.getAsJsonArray(face, "uv", new JsonArray());
            if (uv.size() < 4) continue;
            valid[i] = true;
            u0[i] = uv.get(0).getAsFloat();
            v0[i] = uv.get(1).getAsFloat();
            u1[i] = uv.get(2).getAsFloat();
            v1[i] = uv.get(3).getAsFloat();
        }
    }
}
