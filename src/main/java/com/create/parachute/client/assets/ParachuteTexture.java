package com.create.parachute.client.assets;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 从磁盘/内嵌数据加载的伞面贴图。
 * <p>上传前必须先 {@link TextureUtil#prepareImage} 分配 GL 存储（glTexImage2D），
 * 否则 glTexSubImage2D 会在空纹理上失败（GL_INVALID_VALUE / 显示黑色）。
 * 只上传一次；资源重载时不再重复上传（伞面贴图来自游戏根目录，不随资源包变动）。</p>
 */
public class ParachuteTexture extends AbstractTexture {

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
        // 分配纹理存储（glTexImage2D），与 DynamicTexture 构造函数一致
        TextureUtil.prepareImage(this.getId(), this.image.getWidth(), this.image.getHeight());
        this.image.upload(0, 0, 0, false);
        this.image.close();
        this.uploaded = true;
    }
}
