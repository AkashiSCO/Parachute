package com.create.parachute.client.assets;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 从磁盘/内嵌数据加载的伞面贴图。
 * <p>上传前必须先 {@link TextureUtil#prepareImage} 分配 GL 存储（glTexImage2D），
 * 否则 glTexSubImage2D 会在空纹理上失败（GL_INVALID_VALUE / 显示黑色）。</p>
 *
 * <p><b>mipmap + 线性过滤</b>：DCS 之类的高模贴图常是 2048²，如果只上传 level 0 且用默认
 * NEAREST 过滤，缩小时会碎成规则三角形摩尔纹（看起来像"贴图丢了"）。
 * 这里按 {@code SimpleTexture.doLoad} 的参数顺序上传，生成 mipmap 并开线性过滤。</p>
 */
public class ParachuteTexture extends AbstractTexture {

    /** 生成的 mipmap 级数（2048² 有 4 级就到 128²；目前只用 level 0，见 load 里的说明） */
    private static final int MIP_LEVELS = 4;

    private final NativeImage image;
    private boolean uploaded;

    public ParachuteTexture(NativeImage image) {
        this.image = image;
    }

    @Override
    public void load(ResourceManager resourceManager) {
        if (this.uploaded || this.image == null) {
            return;
        }
        this.bind();
        int w = this.image.getWidth();
        int h = this.image.getHeight();
        // 参数顺序与 SimpleTexture.doLoad 一致：
        //   prepareImage(id, maxMipLevel, width, height)
        //   upload(level, xOffset, yOffset, skipPixels, skipRows, width, height, blur, clamp, mipmap, close)
        TextureUtil.prepareImage(this.getId(), MIP_LEVELS, w, h);
        this.image.upload(0, 0, 0, 0, 0, w, h, false, false, true, true);
        // 线性过滤，但**不**用 mipmap：
        // prepareImage 只分配了 4 级 mip 存储，1~3 级从没写过（内容是全透明）。
        // 开着 mipmap 时缩小的片元会采到那些空层级 → alpha=0 → cutout 的 alpha 测试
        // 把大片几何丢弃（表现为"只剩几根细杆/碎板"）。逐帧路径没事，是因为 MC 的
        // TextureStateShard 会按 RenderType 覆盖贴图过滤状态。
        this.setFilter(true, false);
        this.uploaded = true;
    }
}
