package com.blackannin.drone;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * 客户端专用钩子：把"打开操控台"这类客户端调用从公共代码里隔离出来，
 * 避免专用服务端加载客户端类。仅在物理客户端调用其方法。
 */
public final class DistClientHooks {
    private DistClientHooks() {
    }

    /** 是否为物理客户端（调用方在客户端分支中调用其余方法前检查） */
    public static boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    /** 打开无人机操控台（仅物理客户端） */
    public static void openDroneConsole() {
        if (!isClient()) {
            return;
        }
        Minecraft.getInstance().setScreen(new com.blackannin.drone.client.DroneControlScreen());
    }
}
