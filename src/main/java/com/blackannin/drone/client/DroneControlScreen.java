package com.blackannin.drone.client;

import com.blackannin.drone.ClientConfig;
import com.blackannin.drone.entity.DroneEntity;
import com.blackannin.drone.network.DroneControlPayload;
import com.blackannin.drone.network.RetrieveDronePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class DroneControlScreen extends Screen {
    /** 视场角可调范围（与配置项范围一致） */
    private static final double FOV_MIN = 30.0D;
    private static final double FOV_MAX = 110.0D;

    public DroneControlScreen() {
        super(Component.translatable("gui.blackannin_drone.drone_control.title"));
    }

    @Override
    protected void init() {
        super.init();
        if (findDrone() == null) {
            return;
        }

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        addMoveButton("gui.blackannin_drone.drone_control.forward", centerX - 30, centerY - 60, DroneControlPayload.FORWARD);
        addMoveButton("gui.blackannin_drone.drone_control.backward", centerX - 30, centerY + 20, DroneControlPayload.BACK);
        addMoveButton("gui.blackannin_drone.drone_control.left", centerX - 100, centerY - 20, DroneControlPayload.LEFT);
        addMoveButton("gui.blackannin_drone.drone_control.right", centerX + 40, centerY - 20, DroneControlPayload.RIGHT);
        addMoveButton("gui.blackannin_drone.drone_control.up", centerX - 100, centerY - 60, DroneControlPayload.UP);
        addMoveButton("gui.blackannin_drone.drone_control.down", centerX + 40, centerY - 60, DroneControlPayload.DOWN);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.blackannin_drone.drone_control.reset"),
                        b -> PacketDistributor.sendToServer(new DroneControlPayload(DroneControlPayload.FOLLOW)))
                .bounds(centerX - 95, centerY + 60, 90, 20).build());

        // 回收无人机：服务端校验归属后返还物品
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blackannin_drone.drone_control.retrieve"),
                        b -> {
                            DroneEntity drone = findDrone();
                            if (drone != null) {
                                PacketDistributor.sendToServer(new RetrieveDronePayload(drone.getId()));
                                onClose();
                            }
                        })
                .bounds(centerX + 5, centerY + 60, 90, 20).build());

        addFovSlider(centerX, centerY + 85);
    }

    /** 无人机视场角滑块：仅影响 OBS 无人机画面，不影响玩家视角 */
    private void addFovSlider(int centerX, int y) {
        double current = ClientConfig.DRONE_FOV.get();
        this.addRenderableWidget(new AbstractSliderButton(centerX - 95, y, 190, 20,
                fovMessage(current), (current - FOV_MIN) / (FOV_MAX - FOV_MIN)) {
            @Override
            protected void updateMessage() {
                setMessage(fovMessage(valueToFov(this.value)));
            }

            @Override
            protected void applyValue() {
                ClientConfig.DRONE_FOV.set(valueToFov(this.value));
                ClientConfig.SPEC.save();
            }
        });
    }

    private static Component fovMessage(double fov) {
        return Component.translatable("gui.blackannin_drone.drone_control.fov", (int) Math.round(fov));
    }

    private static double valueToFov(double value) {
        return FOV_MIN + value * (FOV_MAX - FOV_MIN);
    }

    private void addMoveButton(String key, int x, int y, byte action) {
        this.addRenderableWidget(Button.builder(Component.translatable(key),
                        b -> PacketDistributor.sendToServer(new DroneControlPayload(action)))
                .bounds(x, y, 60, 20).build());
    }

    private DroneEntity findDrone() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        return DroneEntity.findOwned(mc.player);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        if (findDrone() == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.blackannin_drone.drone_control.not_found"),
                    this.width / 2, this.height / 2, 0xFF5555);
        } else {
            graphics.drawCenteredString(this.font, Component.translatable("gui.blackannin_drone.drone_control.connected"),
                    this.width / 2, 40, 0x55FF55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
