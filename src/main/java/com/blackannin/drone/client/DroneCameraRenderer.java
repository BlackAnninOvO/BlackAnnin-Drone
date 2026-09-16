package com.blackannin.drone.client;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.ClientConfig;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * 无人机摄像头：每帧在玩家画面渲染<b>之前</b>（{@code RenderFrameEvent.Pre}，主渲染目标已清屏、
 * 玩家画面尚未绘制）用 100% 原版管线（{@code GameRenderer.renderLevel}：天空/光照/雾全部原版）
 * 把无人机视角渲染进主渲染目标，立即缩放到固定输出分辨率的存档 FBO 推送给 OBS；
 * 随后原版照常渲染玩家自己的画面并完全覆盖主目标，玩家视角不受任何影响。
 *
 * <p>时机说明：Pre 阶段主目标已清屏待绘，无人机画面写进去后被玩家画面覆盖，
 * 屏幕上只显示玩家视角；相比"上屏之后"渲染（会占用玩家画面）与"独立 FBO 二次渲染"
 * （状态冲突导致画面残缺）都更可靠。</p>
 */
public final class DroneCameraRenderer {
    private static final int ERROR_LOG_INTERVAL = 600;

    private static RenderTarget saveTarget;
    private static int errorCooldown;

    private DroneCameraRenderer() {
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ClientConfig.SPOUT_ENABLED.get()) {
            destroy();
            return;
        }
        // 游戏暂停时原版不渲染世界，此时渲染无人机画面会残留在屏幕上
        if (mc.isPaused()) {
            return;
        }
        DroneEntity drone = DroneEntity.findOwned(mc.player);
        if (drone == null) {
            return;
        }
        SpoutSender.initIfNeeded();
        if (!SpoutSender.isAvailable()) {
            return;
        }
        if (errorCooldown > 0) {
            errorCooldown--;
        }

        // 保存玩家侧全部可被 renderLevel 触碰的状态
        Entity prevCameraEntity = mc.getCameraEntity();
        CameraType prevCameraType = mc.options.getCameraType();
        boolean prevBobView = mc.options.bobView().get();
        var prevHitResult = mc.hitResult;
        var prevPickEntity = mc.crosshairPickEntity;

        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.bobView().set(false);
            mc.gameRenderer.setRenderHand(false);
            mc.setCameraEntity(drone);

            mc.gameRenderer.renderLevel(event.getPartialTick());

            int width = ClientConfig.SPOUT_WIDTH.get();
            int height = ClientConfig.SPOUT_HEIGHT.get();
            ensureSave(width, height);
            RenderTarget main = mc.getMainRenderTarget();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saveTarget.frameBufferId);
            // 缩放 + 反向 Y 翻转一次完成，Spout 端免翻转
            GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, height, width, 0,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            mc.getMainRenderTarget().bindWrite(false);
            SpoutSender.send(saveTarget.getColorTextureId(), width, height);
        } catch (Throwable t) {
            if (errorCooldown <= 0) {
                BlackAnninsDrone.LOGGER.warn("无人机画面渲染失败，将在后续帧重试", t);
                errorCooldown = ERROR_LOG_INTERVAL;
            }
        } finally {
            // 完整还原：相机实体/视角/摇晃/手部/准星判定，主 FBO 绑定与视口
            mc.setCameraEntity(prevCameraEntity);
            mc.options.setCameraType(prevCameraType);
            mc.options.bobView().set(prevBobView);
            mc.gameRenderer.setRenderHand(true);
            mc.hitResult = prevHitResult;
            mc.crosshairPickEntity = prevPickEntity;
            if (prevCameraEntity != null) {
                // 恢复相机绑定实体，避免 Camera.tick 向无人机眼高收敛（视角拉低问题）
                mc.gameRenderer.getMainCamera().setup(mc.level, prevCameraEntity,
                        !prevCameraType.isFirstPerson(), prevCameraType.isMirrored(),
                        event.getPartialTick().getGameTimeDeltaPartialTick(true));
            }
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void ensureSave(int width, int height) {        if (saveTarget == null || saveTarget.width != width || saveTarget.height != height) {
            destroy();
            saveTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        }
    }

    private static void destroy() {
        if (saveTarget != null) {
            saveTarget.destroyBuffers();
            saveTarget = null;
        }
        SpoutSender.release();
    }
}
