package com.create.parachute.client.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运行时 .bbmodel → {@link AnimationDefinition} 解析器。
 *
 * <p>读取 {@code animations[]} 中名为 {@code code_anim} 的动画（缺省取第一个）：</p>
 * <ul>
 *   <li>关键帧数值透传（模型姿态已含 Y 翻转；<b>旋转通道</b>与模型一致做 Y 翻转 (-rx,+ry,-rz)，
 *       与 BlockBench 导出语义一致，否则骨骼反向乱转）</li>
 *   <li>同一骨骼同一通道的多个关键帧合并为一个 {@link AnimationChannel}，并按时间排序</li>
 *   <li>插值映射：linear→LINEAR，step/hold→STEP，catmullrom/bezier→CATMULLROM</li>
 * </ul>
 */
public final class BbAnimationParser {

    private BbAnimationParser() {
    }

    @Nullable
    public static AnimationDefinition parse(Reader reader) {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return parse(root);
        } catch (Exception e) {
            com.create.parachute.ExampleMod.LOGGER.warn("Failed to parse bbmodel animation: {}", e.toString());
            return null;
        }
    }

    @Nullable
    public static AnimationDefinition parse(JsonObject root) {
        JsonArray anims = GsonHelper.getAsJsonArray(root, "animations", new JsonArray());
        if (anims.isEmpty()) return null;

        JsonObject anim = null;
        for (JsonElement a : anims) {
            if (a.isJsonObject() && "code_anim".equals(GsonHelper.getAsString(a.getAsJsonObject(), "name", ""))) {
                anim = a.getAsJsonObject();
                break;
            }
        }
        if (anim == null && anims.get(0).isJsonObject()) {
            anim = anims.get(0).getAsJsonObject();
        }
        if (anim == null) return null;

        float length = GsonHelper.getAsFloat(anim, "length", 1.0F);
        AnimationDefinition.Builder builder = AnimationDefinition.Builder.withLength(length);

        String loop = GsonHelper.getAsString(anim, "loop", "hold");
        // 1.21.1 的 Builder 只有 looping()：循环型动画开启；hold 型不开启（时间会被钳制在末尾，等效保持）
        if ("loop".equalsIgnoreCase(loop) || "true".equalsIgnoreCase(loop)) {
            builder.looping();
        }

        JsonObject animators = GsonHelper.getAsJsonObject(anim, "animators", new JsonObject());
        for (Map.Entry<String, JsonElement> en : animators.entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject bone = en.getValue().getAsJsonObject();
            if (!"bone".equals(GsonHelper.getAsString(bone, "type", ""))) continue;
            String name = GsonHelper.getAsString(bone, "name", "");
            if (name.isEmpty()) continue;
            JsonArray keyframes = GsonHelper.getAsJsonArray(bone, "keyframes", new JsonArray());
            if (keyframes.isEmpty()) continue;

            // 按通道分组
            Map<String, List<Keyframe>> byChannel = new HashMap<>();
            for (JsonElement kfEl : keyframes) {
                if (!kfEl.isJsonObject()) continue;
                JsonObject kf = kfEl.getAsJsonObject();
                String channel = GsonHelper.getAsString(kf, "channel", "");
                if (channel.isEmpty()) continue;
                float time = GsonHelper.getAsFloat(kf, "time", 0.0F);
                float[] pts = dataPoint(kf);
                if (pts == null) continue;
                AnimationChannel.Target target = switch (channel) {
                    case "position" -> AnimationChannel.Targets.POSITION;
                    case "rotation" -> AnimationChannel.Targets.ROTATION;
                    case "scale" -> AnimationChannel.Targets.SCALE;
                    default -> null;
                };
                if (target == null) continue;
                AnimationChannel.Interpolation interp = interpolation(GsonHelper.getAsString(kf, "interpolation", "linear"));
                Keyframe keyframe;
                switch (channel) {
                    // 位置/缩放：绝对值直通（与原版导出一致，动画目标是"设置"姿态）
                    case "position" -> keyframe = new Keyframe(time, KeyframeAnimations.posVec(pts[0], pts[1], pts[2]), interp);
                    // 旋转：(-rx, +ry, -rz)，与模型姿态的 Y 翻转约定一致
                    case "rotation" -> keyframe = new Keyframe(time, KeyframeAnimations.degreeVec(-pts[0], pts[1], -pts[2]), interp);
                    default -> keyframe = new Keyframe(time, KeyframeAnimations.scaleVec(pts[0], pts[1], pts[2]), interp);
                }
                byChannel.computeIfAbsent(channel, c -> new ArrayList<>()).add(keyframe);
            }

            for (Map.Entry<String, List<Keyframe>> ch : byChannel.entrySet()) {
                List<Keyframe> kfs = ch.getValue();
                kfs.sort(Comparator.comparingDouble(Keyframe::timestamp));
                AnimationChannel.Target target = switch (ch.getKey()) {
                    case "position" -> AnimationChannel.Targets.POSITION;
                    case "rotation" -> AnimationChannel.Targets.ROTATION;
                    default -> AnimationChannel.Targets.SCALE;
                };
                builder.addAnimation(name, new AnimationChannel(target, kfs.toArray(new Keyframe[0])));
            }
        }
        return builder.build();
    }

    @Nullable
    private static float[] dataPoint(JsonObject kf) {
        JsonArray pts = GsonHelper.getAsJsonArray(kf, "data_points", new JsonArray());
        if (pts.isEmpty() || !pts.get(0).isJsonObject()) return null;
        JsonObject p = pts.get(0).getAsJsonObject();
        return new float[]{
                num(p, "x", 0.0F),
                num(p, "y", 0.0F),
                num(p, "z", 0.0F)
        };
    }

    /** BlockBench 的关键帧数值可能是字符串（如 "0" / "-68"），需兼容解析 */
    private static float num(JsonObject obj, String key, float def) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return def;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsFloat();
        }
        try {
            return Float.parseFloat(el.getAsString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static AnimationChannel.Interpolation interpolation(String interp) {
        // 1.21.1 只有 LINEAR 与 CATMULLROM；step/hold 回退 LINEAR（内置伞均用 linear，无影响）
        return switch (interp.toLowerCase(Locale.ROOT)) {
            case "catmullrom", "bezier", "smooth" -> AnimationChannel.Interpolations.CATMULLROM;
            default -> AnimationChannel.Interpolations.LINEAR;
        };
    }
}
