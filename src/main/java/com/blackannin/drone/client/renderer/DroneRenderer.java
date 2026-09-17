package com.blackannin.drone.client.renderer;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.client.model.DroneModel;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class DroneRenderer extends MobRenderer<DroneEntity, DroneModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(BlackAnninsDrone.MODID, "textures/entity/drone.png");

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneModel(context.bakeLayer(DroneModel.LAYER_LOCATION)), 0.3F);
    }

    @Override
    protected void scale(DroneEntity entity, PoseStack poseStack, float partialTick) {
        // Blockbench 实体模型导出时原点已落在实体脚下，原版渲染器的 -1.501 平移即用于该对齐，
        // 这里不再额外偏移，使模型与碰撞箱重合。
        // （此前多余的 -1.5 平移会把模型推离碰撞箱）
    }

    @Override
    protected void setupRotations(DroneEntity entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        // 原版把模型 -Z 对准朝向；再转 180° 让 Blockbench 的 S（+Z）对准玩家
        super.setupRotations(entity, poseStack, bob, yBodyRot + 180.0F, partialTick, scale);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return TEXTURE;
    }
}
