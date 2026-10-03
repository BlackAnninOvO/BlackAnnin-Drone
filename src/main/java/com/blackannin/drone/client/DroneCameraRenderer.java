package com.blackannin.drone.client;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.ClientConfig;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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
 * 无人机摄像头：每帧在玩家画面渲染完成之后（{@code RenderFrameEvent.Post}，玩家帧
 * （含 Iris 光影的 gbuffer + composite 合成）已完整落盘到主渲染目标），先备份主渲染目标，
 * 再用完整渲染管线渲染无人机视角（Iris 存在时走第二个完整光影管线），拷贝到固定输出分辨率
 * 推送给 OBS，最后恢复主渲染目标——屏幕上玩家看到的画面与未开启推流时完全一致。
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
    /** 渲染帧计数：与推流帧率阀门配合 */
    private static int frameCounter;
    /** 排障导出冷却：spoutDebugDump 开启时按固定间隔覆盖导出帧 */
    private static int dumpCooldown;
    /** 上次推流布局（变化时打印一次诊断日志） */
    private static int lastDstW = -1, lastDstH = -1, lastSrcW = -1, lastSrcH = -1;

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
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ClientConfig.SPOUT_ENABLED.get()) {
            destroy();
            return;
        }
        DroneEntity drone = DroneEntity.findOwned(mc.player);
        if (drone == null) {
            return;
        }
        // 推流帧率阀门：跳过的帧不渲染也不推送，OBS 侧会保持上一帧画面。
        // 开启 Iris 光影时无人机画面需要第二个完整渲染管线，可用此阀门减半性能开销。
        if (++frameCounter % Math.max(1, ClientConfig.SPOUT_EVERY_N_FRAMES.get()) != 0) {
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
        ensureBackupTarget(mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);

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

            // 备份玩家画面（含 HUD）：无人机帧渲染会覆盖主渲染目标，推流后恢复，屏幕不受影响
            copyRenderTarget(mc.getMainRenderTarget(), backupTarget);
            // GUI 绘制可能残留剪裁/掩码状态，会让清屏与绘制被裁到局部区域（画面残缺）
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._depthMask(true);
            // 渲染到主渲染目标：Sodium/光影等地形渲染器只认这个目标。
            // Iris 光影下，MixinLevelRenderer 对 renderLevel 的字节码注入（beginLevelRendering/
            // finalizeLevelRendering）会在本次直接调用中照常执行，完整跑一遍 Iris 管线
            // （gbuffer + composite 合成），合成结果输出到主渲染目标，随后拷贝推送。
            // 玩家帧稍后由原版流程重新渲染并覆盖主目标，玩家视角不受影响。
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
            // 排障：spoutDebugDump 开启时按固定间隔覆盖导出实际推送帧
            if (ClientConfig.SPOUT_DEBUG_DUMP.get() && dumpCooldown-- <= 0) {
                dumpStreamFrame();
                dumpCooldown = 60;
            }
            if (!streamLogged) {
                streamLogged = true;
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
            // 恢复玩家画面（含 HUD）：无人机帧只用于推流，屏幕上的玩家画面原样保留
            copyRenderTarget(backupTarget, mc.getMainRenderTarget());
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** 主渲染目标备份（与窗口同尺寸，仅颜色通道） */
    private static RenderTarget backupTarget;

    /** 窗口尺寸变化时重建备份目标 */
    private static void ensureBackupTarget(int width, int height) {
        if (backupTarget == null || backupTarget.width != width || backupTarget.height != height) {
            if (backupTarget != null) {
                backupTarget.destroyBuffers();
            }
            backupTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        }
    }

    /**
     * 两个帧缓冲间的颜色拷贝（备份/恢复玩家画面用）。
     *
     * <p>关键：{@code glBlitFramebuffer} 会被剪裁测试（scissor）与颜色掩码裁剪。
     * 玩家帧渲染后 GUI 常残留剪裁状态，若不清除，恢复只会覆盖局部区域，
     * 区域外仍显示无人机帧的内容——表现为玩家视角里出现"无人机看到的画面"虚影。</p>
     */
    private static void copyRenderTarget(RenderTarget from, RenderTarget to) {
        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(true);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
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
        GlStateManager._depthMask(true);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saveTarget.frameBufferId);

        // 1) 整帧清成【全透明】：游戏画面之外的区域在 OBS 中即为透明，而非黑边
        GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);

        // 2) 画面区域：默认保持窗口宽高比居中（可配置为拉伸铺满），四周多余部分保持透明
        int dstX = 0;
        int dstY = 0;
        int dstW = width;
        int dstH = height;
        if (ClientConfig.SPOUT_KEEP_ASPECT.get() && main.width > 0 && main.height > 0) {
            double srcAspect = main.width / (double) main.height;
            double dstAspect = width / (double) height;
            if (srcAspect > dstAspect) {
                dstW = width;
                dstH = Math.max(1, (int) Math.round(width / srcAspect));
            } else {
                dstH = height;
                dstW = Math.max(1, (int) Math.round(height * srcAspect));
            }
            dstX = (width - dstW) / 2;
            dstY = (height - dstH) / 2;
        }

        // 排障：布局变化时打印一次实际画面区域（确认缩放/透明边是否符合预期）
        if (dstW != lastDstW || dstH != lastDstH || main.width != lastSrcW || main.height != lastSrcH) {
            lastDstW = dstW;
            lastDstH = dstH;
            lastSrcW = main.width;
            lastSrcH = main.height;
            BlackAnninsDrone.LOGGER.info("推流帧布局: 画面 {}x{} @({}, {}) 于 {}x{} 画布 | 游戏窗口 {}x{} | 保持宽高比={}",
                    dstW, dstH, dstX, dstY, width, height, main.width, main.height,
                    ClientConfig.SPOUT_KEEP_ASPECT.get());
        }

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, main.width, main.height,
                dstX, dstY + dstH, dstX + dstW, dstY,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

        // 3) 画面区域内强制不透明（alpha=1）：只写 alpha 通道，四周透明区不受影响
        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(dstX, dstY, dstW, dstH);
        GlStateManager._colorMask(false, false, false, true);
        GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableScissorTest();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
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
        if (backupTarget != null) {
            backupTarget.destroyBuffers();
            backupTarget = null;
        }
        SpoutSender.release();
    }
}
