package com.blackannin.drone.client;

import com.blackannin.drone.ClientConfig;
import com.blackannin.drone.entity.DroneEntity;
import com.blackannin.drone.network.RemoteControlPayload;
import com.blackannin.drone.network.RetrieveDronePayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 无人机操控台：航测风格遥测面板 + 手动操控。
 *
 * <p>打开面板默认处于"观察"状态（无人机自动跟随），点击「手动操纵」或按 K 接管后
 * 才响应键鼠：WASD/空格/Shift 三轴连续移动。视角两种模式：鼠标直接控制（接管后锁定
 * 光标，移动鼠标无限旋转，不受窗口边界限制，UI 操作改用键位 K/R/X/V）；摇杆渐进控制
 * （拖动摇杆，视角以可调灵敏度的角速度跟随，松手回中，光标正常显示用于拖动 UI）。
 * 「恢复跟随」(R) 彻底交还控制权，(X) 回收无人机。关闭面板后无人机保持当前模式。</p>
 */
public class DroneControlScreen extends Screen {
    /** 面板配色 */
    private static final int ACCENT = 0xFF00D9FF;
    private static final int TEXT = 0xFFDDEEFF;
    private static final int DIM = 0xFF8FA3B8;
    private static final int PANEL = 0xD00C1116;
    private static final int PANEL_EDGE = 0xFF1E2C38;
    private static final int OK = 0xFF3DE08A;
    private static final int WARN = 0xFFFFB13D;
    /** 摇杆灵敏度范围（每 tick 满偏时的像素当量） */
    private static final double SENS_MIN = 0.5D, SENS_MAX = 12.0D;

    /** 键盘状态 */
    private boolean keyFwd, keyBack, keyLeft, keyRight, keyUp, keyDown;
    /** 视角输入累计（像素/摇杆当量），tick 时随输入包发送并清零 */
    private float pendingYaw, pendingPitch;
    private double lastMouseX = Double.MIN_VALUE, lastMouseY;

    /** 是否已接管手动操纵：接管后才响应键鼠，恢复跟随后彻底停发输入 */
    private boolean manualActive;
    /** 是否已从服务端继承手动状态（面板首次拿到无人机时做一次，之后本地操作优先） */
    private boolean stateSynced;
    /** 视角模式：true = 摇杆渐进控制，false = 鼠标直接控制 */
    private boolean stickMode;
    /** 摇杆偏移 [-1,1]，松手回中 */
    private double stickX, stickY;
    private boolean stickDragging;
    /** 摇杆灵敏度 */
    private double stickSens = 1.5D;
    private boolean sliderDragging;
    /** 当前是否锁定了光标（鼠标模式接管中） */
    private boolean cursorLocked;

    private long animStart;

    private DroneEntity drone;
    private FlatButton takeoverButton;
    private FlatButton followButton;
    private FlatButton retrieveButton;
    private FlatButton modeButton;
    private FlatButton resetViewButton;
    /** 摇杆与滑块几何（render 与鼠标交互共用） */
    private int stickCx, stickCy, stickR;
    private int sliderX, sliderY, sliderW;

    public DroneControlScreen() {
        super(Component.translatable("gui.blackannin_drone.drone_control.title"));
    }

    @Override
    protected void init() {
        super.init();
        this.drone = findDrone();
        this.animStart = Util.getMillis();
        int px = this.width / 2 - 165;
        int py = this.height / 2 - 100;
        stickR = 19;
        stickCx = px + 36;
        stickCy = py + 152;
        sliderX = px + 116;
        sliderY = py + 155;
        sliderW = 72;

        int bx = this.width / 2 - 163;
        int by = this.height / 2 + 106;
        takeoverButton = new FlatButton(bx, by, 98, 18, "gui.blackannin_drone.drone_control.takeover", this::startManual);
        followButton = new FlatButton(bx + 106, by, 98, 18, "gui.blackannin_drone.drone_control.reset", this::stopManual);
        retrieveButton = new FlatButton(bx + 212, by, 98, 18, "gui.blackannin_drone.drone_control.retrieve", this::retrieveDrone);
        modeButton = new FlatButton(px + 66, py + 128, 72, 16, "gui.blackannin_drone.drone_control.mode_mouse", this::toggleStickMode);
        resetViewButton = new FlatButton(px + 146, py + 128, 86, 16, "gui.blackannin_drone.drone_control.reset_view", this::resetView);
        // 默认视角模式取自客户端配置（默认摇杆/手柄模式），面板重开或窗口调整后保持一致
        this.stickMode = ClientConfig.STICK_VIEW_MODE.get();
        modeButton.setLabel(modeLabel(this.stickMode));
        syncButtons();
    }

    /** 进入手动操纵：发接管标志，此后键鼠输入才生效 */
    private void startManual() {
        if (manualActive) {
            return;
        }
        manualActive = true;
        PacketDistributor.sendToServer(new RemoteControlPayload(0, 0, 0, 0, 0, RemoteControlPayload.FLAG_TAKEOVER));
        syncButtons();
    }

    /** 恢复跟随：彻底停止本地操纵（清按键、停发输入），鼠标此后不会再触发接管 */
    private void stopManual() {
        manualActive = false;
        releaseKeys();
        stickX = 0;
        stickY = 0;
        PacketDistributor.sendToServer(new RemoteControlPayload(0, 0, 0, 0, 0, RemoteControlPayload.FLAG_FOLLOW));
        syncButtons();
    }

    /** 重置视角：无人机对准玩家朝向、俯仰归零（鼠标模式锁定光标时用 C 键） */
    private void resetView() {
        PacketDistributor.sendToServer(new RemoteControlPayload(0, 0, 0, 0, 0, RemoteControlPayload.FLAG_RESET_VIEW));
    }

    /** 切换摇杆/鼠标视角模式（选择写入客户端配置，下次进入沿用） */
    private void toggleStickMode() {
        stickMode = !stickMode;
        stickX = 0;
        stickY = 0;
        pendingYaw = 0;
        pendingPitch = 0;
        modeButton.setLabel(modeLabel(stickMode));
        ClientConfig.STICK_VIEW_MODE.set(stickMode);
        updateCursorLock();
    }

    private static Component modeLabel(boolean stick) {
        return Component.translatable(stick
                ? "gui.blackannin_drone.drone_control.mode_stick"
                : "gui.blackannin_drone.drone_control.mode_mouse");
    }

    /** 根据接管与视角模式锁定/释放光标：鼠标模式接管中锁定，获得无限旋转的相对增量 */
    private void updateCursorLock() {
        boolean lock = manualActive && !stickMode;
        if (lock == cursorLocked) {
            return;
        }
        cursorLocked = lock;
        long window = Minecraft.getInstance().getWindow().getWindow();
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR,
                lock ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        // 光标模式切换会产生一次位置跳变，重置基准避免视角猛甩
        lastMouseX = Double.MIN_VALUE;
    }

    /** 同步接管/恢复按钮的可用状态：跟随服务端手动状态，互斥点亮 */
    private void syncButtons() {
        boolean serverManual = drone != null && drone.isManuallyControlled();
        takeoverButton.setActive(!serverManual);
        followButton.setActive(serverManual);
    }

    private void releaseKeys() {
        keyFwd = keyBack = keyLeft = keyRight = keyUp = keyDown = false;
    }

    @Override
    public void tick() {
        super.tick();
        updateCursorLock();
        this.drone = findDrone();
        if (drone == null) {
            pendingYaw = 0;
            pendingPitch = 0;
            return;
        }
        // 首次拿到无人机时继承服务端的手动状态：接管中关面板再打开，可直接恢复跟随或继续操纵
        if (!stateSynced) {
            stateSynced = true;
            manualActive = drone.isManuallyControlled();
            syncButtons();
        }
        syncButtons();
        if (!manualActive) {
            // 观察态不发送任何输入：鼠标移动绝不会误触发接管
            pendingYaw = 0;
            pendingPitch = 0;
            return;
        }
        // 摇杆模式：偏移量作为角速度，每 tick 累计，视角缓慢跟随（下拉=低头，与直觉一致）
        if (stickMode) {
            pendingYaw += (float) (stickX * stickSens);
            pendingPitch += (float) (stickY * stickSens);
        }
        float fwd = (keyFwd ? 1 : 0) + (keyBack ? -1 : 0);
        float strafe = (keyRight ? 1 : 0) + (keyLeft ? -1 : 0);
        float up = (keyUp ? 1 : 0) + (keyDown ? -1 : 0);
        PacketDistributor.sendToServer(new RemoteControlPayload(fwd, strafe, up, pendingYaw, pendingPitch, (byte) 0));
        pendingYaw = 0;
        pendingPitch = 0;
    }

    @Override
    public void removed() {
        // 关闭面板：恢复光标、清零输入，无人机保持当前模式（手动则原地悬停）
        if (cursorLocked) {
            cursorLocked = false;
            long window = Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        PacketDistributor.sendToServer(new RemoteControlPayload(0, 0, 0, 0, 0, (byte) 0));
        super.removed();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_K -> {
                if (!manualActive) {
                    startManual();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_R -> {
                if (manualActive) {
                    stopManual();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_V -> {
                toggleStickMode();
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                resetView();
                return true;
            }
            case GLFW.GLFW_KEY_X -> {
                retrieveDrone();
                return true;
            }
        }
        setKey(keyCode, true);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        setKey(keyCode, false);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void setKey(int keyCode, boolean down) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_W -> keyFwd = down;
            case GLFW.GLFW_KEY_S -> keyBack = down;
            case GLFW.GLFW_KEY_A -> keyLeft = down;
            case GLFW.GLFW_KEY_D -> keyRight = down;
            case GLFW.GLFW_KEY_SPACE -> keyUp = down;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> keyDown = down;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 光标锁定（鼠标模式接管中）时指针不可见，忽略全部 UI 点击，避免虚拟坐标误触
        if (cursorLocked) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            if (inStick(mouseX, mouseY)) {
                stickDragging = true;
                updateStick(mouseX, mouseY);
                return true;
            }
            if (mouseX >= sliderX - 4 && mouseX <= sliderX + sliderW + 4
                    && mouseY >= sliderY - 6 && mouseY <= sliderY + 10) {
                sliderDragging = true;
                updateSlider(mouseX);
                return true;
            }
            if (modeButton.click(mouseX, mouseY, button) | resetViewButton.click(mouseX, mouseY, button)
                    | takeoverButton.click(mouseX, mouseY, button) | followButton.click(mouseX, mouseY, button)
                    | retrieveButton.click(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (stickDragging) {
            stickDragging = false;
            stickX = 0;
            stickY = 0;
        }
        sliderDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (stickDragging) {
            updateStick(mouseX, mouseY);
        } else if (sliderDragging) {
            updateSlider(mouseX);
        } else if (!stickMode) {
            // 鼠标模式：仅在手动接管状态下累计视角增量（锁定光标后为无限相对旋转）
            if (manualActive && lastMouseX != Double.MIN_VALUE) {
                pendingYaw += (float) (mouseX - lastMouseX);
                pendingPitch += (float) (mouseY - lastMouseY);
            }
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    private boolean inStick(double mouseX, double mouseY) {
        double dx = mouseX - stickCx;
        double dy = mouseY - stickCy;
        return dx * dx + dy * dy <= (stickR + 4) * (stickR + 4);
    }

    private void updateStick(double mouseX, double mouseY) {
        double dx = mouseX - stickCx;
        double dy = mouseY - stickCy;
        double max = stickR - 4;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len > max) {
            dx = dx * max / len;
            dy = dy * max / len;
        }
        stickX = dx / max;
        stickY = dy / max;
    }

    private void updateSlider(double mouseX) {
        double t = (mouseX - sliderX) / (double) sliderW;
        stickSens = Mth.clamp(SENS_MIN + t * (SENS_MAX - SENS_MIN), SENS_MIN, SENS_MAX);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, this.width, this.height, 0x60060A0E, 0x90060A0E);
        int px = this.width / 2 - 165;
        int py = this.height / 2 - 100;
        int pw = 330;
        int ph = 200;

        g.fill(px, py, px + pw, py + ph, PANEL);
        g.renderOutline(px, py, pw, ph, PANEL_EDGE);
        g.fill(px, py, px + pw, py + 1, ACCENT);

        boolean online = drone != null;
        g.drawString(this.font, this.title, px + 10, py + 8, ACCENT);
        long t = Util.getMillis() - animStart;
        int dot = online ? pulse(t) : WARN;
        g.fill(px + pw - 16, py + 9, px + pw - 10, py + 15, dot);
        g.drawString(this.font, online ? "LINK" : "NO LINK", px + pw - 52, py + 8, online ? OK : WARN);

        DroneHudRenderer.compass(g, this.font, drone, px + 10, py + 22, pw - 20);
        DroneHudRenderer.horizon(g, this.font, drone, px + 10, py + 46, 130, 56);
        DroneHudRenderer.telemetry(g, this.font, drone, px + 152, py + 46, px + pw - 12);
        renderStickArea(g, px, py, mouseX, mouseY);

        String hints1 = "WASD " + hint("hint_move") + " · SPACE/SHIFT " + hint("hint_updown")
                + " · " + hint("hint_gating");
        String hints2 = cursorLocked
                ? "V " + hint("hint_unlock") + " · C " + hint("hint_reset_view") + " · R " + hint("hint_follow")
                : "K " + hint("hint_takeover") + " · R " + hint("hint_follow")
                        + " · X " + hint("hint_retrieve") + " · V " + hint("hint_switch");
        g.drawString(this.font, hints1, px + 10, py + ph - 20, DIM);
        g.drawString(this.font, hints2, px + 10, py + ph - 10, DIM);

        resetViewButton.render(g, this.font, mouseX, mouseY);
        takeoverButton.render(g, this.font, mouseX, mouseY);
        followButton.render(g, this.font, mouseX, mouseY);
        retrieveButton.render(g, this.font, mouseX, mouseY);
    }

    private String hint(String key) {
        return Component.translatable("gui.blackannin_drone.drone_control." + key).getString();
    }

    /** 底部工具区：摇杆（仅摇杆模式显示）+ 视角模式按钮 + 灵敏度滑块（同一行垂直居中对齐） */
    private void renderStickArea(GuiGraphics g, int px, int py, int mouseX, int mouseY) {
        if (!stickMode) {
            // 鼠标模式：摇杆无意义，不渲染，避免误导
            modeButton.render(g, this.font, mouseX, mouseY);
            g.drawString(this.font, hint("hint_mouse_direct"), px + 66, py + 153, DIM);
            return;
        }
        // 摇杆底座（扫描线圆盘）与外圈描边
        for (int dy = -stickR; dy <= stickR; dy++) {
            int half = (int) Math.round(Math.sqrt(stickR * stickR - dy * dy));
            int color = Math.abs(dy) > stickR - 2 ? PANEL_EDGE : 0xFF101A22;
            g.fill(stickCx - half, stickCy + dy, stickCx + half, stickCy + dy + 1, color);
        }
        g.fill(stickCx - 1, stickCy - stickR + 4, stickCx, stickCy + stickR - 4, PANEL_EDGE);
        g.fill(stickCx + 1, stickCy - stickR + 4, stickCx + 2, stickCy + stickR - 4, PANEL_EDGE);
        g.fill(stickCx - stickR + 4, stickCy - 1, stickCx + stickR - 4, stickCy, PANEL_EDGE);
        g.fill(stickCx - stickR + 4, stickCy + 1, stickCx + stickR - 4, stickCy + 2, PANEL_EDGE);

        int knob = (int) Math.round(stickX * (stickR - 4));
        int knobY = (int) Math.round(stickY * (stickR - 4));
        boolean hover = inStick(mouseX, mouseY) || stickDragging;
        g.fill(stickCx + knob - 5, stickCy + knobY - 5, stickCx + knob + 5, stickCy + knobY + 5,
                stickDragging ? ACCENT : hover ? 0xFF2A4B5E : 0xFF1E3A4A);
        g.renderOutline(stickCx + knob - 5, stickCy + knobY - 5, 10, 10, ACCENT);

        modeButton.render(g, this.font, mouseX, mouseY);
        // 灵敏度行与滑块同一水平带（文本 8px 高与滑块游标中心对齐）
        if (stickMode) {
            g.drawString(this.font, hint("hint_sens"), px + 66, py + 153, DIM);
            g.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 4, 0xFF101A22);
            int fill = (int) Math.round((stickSens - SENS_MIN) / (SENS_MAX - SENS_MIN) * sliderW);
            g.fill(sliderX, sliderY, sliderX + fill, sliderY + 4, ACCENT);
            int knobX = sliderX + fill - 2;
            g.fill(knobX, sliderY - 3, knobX + 4, sliderY + 7, TEXT);
            g.drawString(this.font, String.format("%.1f", stickSens), sliderX + sliderW + 6, py + 153, TEXT);
        } else {
            g.drawString(this.font, hint("hint_mouse_direct"), px + 66, py + 153, DIM);
        }
    }

    /** LINK 状态灯呼吸脉冲（绿） */
    private int pulse(long t) {
        int v = (int) (0xE0 * (0.55F + 0.45F * (float) Math.abs(Math.sin(t / 500.0))));
        return 0xFF000000 | (v << 8) | v;
    }

    /** 回收无人机：校验归属后返还物品并关闭面板 */
    private void retrieveDrone() {
        DroneEntity d = findDrone();
        if (d != null) {
            PacketDistributor.sendToServer(new RetrieveDronePayload(d.getId()));
            onClose();
        }
    }

    /** 查找归属本玩家的无人机 */
    private DroneEntity findDrone() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : DroneEntity.findOwned(mc.player);
    }


}
