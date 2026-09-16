package com.blackannin.drone.client.model;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.resources.ResourceLocation;

/** Blockbench 导出的 Java 实体模型，贴图为 32x32 图集 textures/entity/drone.png */
public class DroneModel extends EntityModel<DroneEntity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(BlackAnninsDrone.MODID, "drone"), "main");
            
    private final ModelPart propeller1;
    private final ModelPart propeller2;
    private final ModelPart propeller3;
    private final ModelPart propeller4;
    private final ModelPart bb_main;

    public DroneModel(ModelPart root) {
        this.propeller1 = root.getChild("propeller1");
        this.propeller2 = root.getChild("propeller2");
        this.propeller3 = root.getChild("propeller3");
        this.propeller4 = root.getChild("propeller4");
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition propeller1 = partdefinition.addOrReplaceChild("propeller1", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 23).addBox(-2.0F, -1.0F, 3.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(2, 1).addBox(-1.0F, -1.0F, 1.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.1F))
                .texOffs(0, 15).addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(-3.0F, 23.0F, -4.0F));

        propeller1.addOrReplaceChild("blade_r1", CubeListBuilder.create().texOffs(0, 15).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.0F, -1.5708F, 0.0F));
        propeller1.addOrReplaceChild("guard_r1", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));
        propeller1.addOrReplaceChild("guard_r2", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));

        PartDefinition propeller2 = partdefinition.addOrReplaceChild("propeller2", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 23).addBox(-2.0F, -1.0F, 3.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(2, 1).addBox(-1.0F, -1.0F, 1.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.1F))
                .texOffs(0, 15).addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(-3.0F, 23.0F, 2.0F));

        propeller2.addOrReplaceChild("blade_r2", CubeListBuilder.create().texOffs(0, 15).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.0F, -1.5708F, 0.0F));
        propeller2.addOrReplaceChild("guard_r3", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));
        propeller2.addOrReplaceChild("guard_r4", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));

        PartDefinition propeller3 = partdefinition.addOrReplaceChild("propeller3", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 23).addBox(-2.0F, -1.0F, 3.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(2, 1).addBox(-1.0F, -1.0F, 1.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.1F))
                .texOffs(0, 15).addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(5.0F, 23.0F, -4.0F));

        propeller3.addOrReplaceChild("blade_r3", CubeListBuilder.create().texOffs(0, 15).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.0F, -1.5708F, 0.0F));
        propeller3.addOrReplaceChild("guard_r5", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));
        propeller3.addOrReplaceChild("guard_r6", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));

        PartDefinition propeller4 = partdefinition.addOrReplaceChild("propeller4", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 23).addBox(-2.0F, -1.0F, 3.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(2, 1).addBox(-1.0F, -1.0F, 1.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.1F))
                .texOffs(0, 15).addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(5.0F, 23.0F, 2.0F));

        propeller4.addOrReplaceChild("blade_r4", CubeListBuilder.create().texOffs(0, 15).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.0F, -1.5708F, 0.0F));
        propeller4.addOrReplaceChild("guard_r7", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));
        propeller4.addOrReplaceChild("guard_r8", CubeListBuilder.create().texOffs(0, 23).addBox(-2.0F, -1.0F, -1.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, 0.0F, 2.0F, 0.0F, -1.5708F, 0.0F));

        partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(12, 22).addBox(-1.0F, -3.0F, -3.0F, 3.0F, 3.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(0, 28).addBox(-0.5F, -1.75F, 3.7F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 32, 32);
    }

    @Override
    public void setupAnim(DroneEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        // 按需求保持螺旋桨静止
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        propeller1.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        propeller2.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        propeller3.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        propeller4.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}
