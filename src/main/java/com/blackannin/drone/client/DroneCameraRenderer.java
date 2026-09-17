package com.blackannin.drone.client;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.ClientConfig;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 无人机摄像头：每帧在玩家画面渲染之前（{@code RenderFrameEvent.Pre}，此时 GL 状态与
 * 原版自身的世界渲染完全一致，不会被 GUI 的剪裁状态截断），用 100% 原版渲染管线
 * （{@code GameRenderer.renderLevel}：天空/光照/雾全部原版，光影同样生效）把无人机视角渲染到
 * <b>独立 FBO</b>，再缩放拷贝到固定输出分辨率推送给 OBS。
 *
 * <p>关键：全程不读写主渲染目标，玩家自己的画面与 HUD 由原版渲染一次、完全不受本模组影响。
 * {@link com.blackannin.drone.mixin.client.MinecraftMixin} 在无人机渲染期间把
 * {@code getMainRenderTarget()} 重定向到无人机 FBO，使渲染管线写入正确目标；
 * 无人机 FBO 与窗口同尺寸，避免渲染途中视口变化导致画面残缺。</p>
 */
public final class DroneCameraRenderer {
    private static final int ERROR_LOG_INTERVAL = 600;
    /** Camera.tick() 每次收敛 50%，10 次即可使眼高误差 < 0.01 格 */
    private static final int EYE_HEIGHT_CONVERGE_TICKS = 10;

    /** 固定输出分辨率的推流帧 */
    private static RenderTarget saveTarget;
    /** 无人机视角渲染中标记 */
    private static boolean renderingDrone;
    private static int errorCooldown;
    /** 每次会话只输出一次推流确认日志 */
    private static boolean streamLogged;

    private DroneCameraRenderer() {
    }

    /** 无人机视角使用固定 FOV，不随玩家视场角设置（含疾跑变化）改变 */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (renderingDrone) {
            event.setFOV(ClientConfig.DRONE_FOV.get());
        }
    }

    /** 世界卸载或关闭推流时释放显存与 Spout 资源 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ClientConfig.SPOUT_ENABLED.get()) {
            destroy();
        }
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ClientConfig.SPOUT_ENABLED.get()) {
            destroy();
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

        int outWidth = ClientConfig.SPOUT_WIDTH.get();
        int outHeight = ClientConfig.SPOUT_HEIGHT.get();
        ensureTargets(outWidth, outHeight);

        // 保存玩家侧全部可被 renderLevel 触碰的状态
        Entity prevCameraEntity = mc.getCameraEntity();
        CameraType prevCameraType = mc.options.getCameraType();
        boolean prevBobView = mc.options.bobView().get();
        int prevFov = mc.options.fov().get();
        var prevHitResult = mc.hitResult;
        var prevPickEntity = mc.crosshairPickEntity;
        Camera camera = mc.gameRenderer.getMainCamera();
        // 保存相机眼高（原版每 tick 50% 缓动状态），渲染后精确还原，
        // 否则潜行等眼高过渡会被我们的收敛循环"瞬移"掉，视角出现跳变
        float prevEyeHeight = camera.eyeHeight;
        float prevEyeHeightOld = camera.eyeHeightOld;

        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.bobView().set(false);
            // FOV 双重锁定：事件覆盖 + 选项值，屏蔽玩家视场角与疾跑变化
            mc.options.fov().set((int) Math.round(ClientConfig.DRONE_FOV.get()));
            mc.gameRenderer.setRenderHand(false);
            mc.setCameraEntity(drone);

            // 共享主相机眼高由 Camera.tick() 向绑定实体收敛；先绑定无人机并收敛，
            // 否则会沿用玩家眼高，使画面比无人机模型高出约 1.4 格
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            camera.setup(mc.level, drone, false, false, partialTick);
            for (int i = 0; i < EYE_HEIGHT_CONVERGE_TICKS; i++) {
                camera.tick();
            }

            // GUI 绘制可能残留剪裁/掩码状态，会让清屏与绘制被裁到局部区域（画面残缺）
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._depthMask(true);
            // 渲染到主渲染目标：Sodium/光影等地形渲染器只认这个目标，
            // 渲染完拷贝出来，随后原版会用自己的画面完整覆盖主目标，玩家视角不受影响
            RenderTarget main = mc.getMainRenderTarget();
            main.bindWrite(true);
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._depthMask(true);
            renderingDrone = true;
            mc.gameRenderer.renderLevel(event.getPartialTick());
            renderingDrone = false;

            blitToSave(mc, outWidth, outHeight);
            SpoutSender.send(saveTarget.getColorTextureId(), outWidth, outHeight);
            if (!streamLogged) {
                streamLogged = true;
                dumpStreamFrame();
                Camera droneCamera = mc.gameRenderer.getMainCamera();
                BlackAnninsDrone.LOGGER.info("开始推流无人机视角: 发送器={} 分辨率={}x{} 相机=({}, {}, {}) 模组版本={}",
                        ClientConfig.SPOUT_SENDER_NAME.get(), outWidth, outHeight,
                        String.format("%.1f", droneCamera.getPosition().x),
                        String.format("%.1f", droneCamera.getPosition().y),
                        String.format("%.1f", droneCamera.getPosition().z),
                        BlackAnninsDrone.class.getPackage().getImplementationVersion());
            }
        } catch (Throwable t) {
            if (errorCooldown <= 0) {
                BlackAnninsDrone.LOGGER.warn("无人机画面渲染失败，将在后续帧重试", t);
                errorCooldown = ERROR_LOG_INTERVAL;
            }
        } finally {
            renderingDrone = false;
            // 还原玩家侧状态，主渲染目标交由原版流程继续使用
            mc.setCameraEntity(prevCameraEntity);
            mc.options.setCameraType(prevCameraType);
            mc.options.bobView().set(prevBobView);
            mc.options.fov().set(prevFov);
            mc.gameRenderer.setRenderHand(true);
            mc.hitResult = prevHitResult;
            mc.crosshairPickEntity = prevPickEntity;
            // 还原眼高（两帧字段一起还原，保持原版插值状态），再把相机绑定回玩家实体
            camera.eyeHeight = prevEyeHeight;
            camera.eyeHeightOld = prevEyeHeightOld;
            if (prevCameraEntity != null) {
                camera.setup(mc.level, prevCameraEntity,
                        !prevCameraType.isFirstPerson(), prevCameraType.isMirrored(),
                        event.getPartialTick().getGameTimeDeltaPartialTick(true));
            }
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** 排障：把实际发送的推流帧导出为 PNG（游戏目录 drone_stream_debug.png） */
    private static void dumpStreamFrame() {
        if (!ClientConfig.SPOUT_DEBUG_DUMP.get()) {
            return;
        }
        com.mojang.blaze3d.platform.NativeImage image = null;
        try {
            image = new com.mojang.blaze3d.platform.NativeImage(saveTarget.width, saveTarget.height, false);
            GlStateManager._bindTexture(saveTarget.getColorTextureId());
            image.downloadTexture(0, false);
            image.writeToFile(java.nio.file.Path.of("drone_stream_debug.png").toFile());
            BlackAnninsDrone.LOGGER.info("已导出推流帧供排障: drone_stream_debug.png");
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("导出推流帧失败: {}", t.toString());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /** 主渲染目标 → 推流帧的缩放 + 垂直翻转拷贝 */
    private static void blitToSave(Minecraft mc, int width, int height) {
        RenderTarget main = mc.getMainRenderTarget();
        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saveTarget.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, height, width, 0,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        main.bindWrite(false);
    }

    /** 推流帧尺寸跟随配置，改变后下一帧立即重建（无需重启） */
    private static void ensureTargets(int outWidth, int outHeight) {
        if (saveTarget == null || saveTarget.width != outWidth || saveTarget.height != outHeight) {
            if (saveTarget != null) {
                saveTarget.destroyBuffers();
            }
            saveTarget = new TextureTarget(outWidth, outHeight, false, Minecraft.ON_OSX);
        }
    }

    private static void destroy() {
        streamLogged = false;
        if (saveTarget != null) {
            saveTarget.destroyBuffers();
            saveTarget = null;
        }
        SpoutSender.release();
    }
}
