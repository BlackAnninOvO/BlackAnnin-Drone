package com.blackannin.drone;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 仅影响客户端：FPV 相机与 Spout/OBS 输出 */
public class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SPOUT_ENABLED = BUILDER
            .comment("启用无人机 FPV 相机，并把画面通过 Spout2 发给 OBS（分辨率跟随游戏窗口，调整窗口大小即时生效）")
            .define("spoutEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> SPOUT_SENDER_NAME = BUILDER
            .comment("OBS Spout 源里显示的发送器名称")
            .define("spoutSenderName", "BlackAnninDrone");

    public static final ModConfigSpec.IntValue SPOUT_WIDTH = BUILDER
            .comment("FPV 输出宽度（游戏内修改后下一帧立即生效，无需重启）")
            .defineInRange("spoutWidth", 1280, 256, 3840);

    public static final ModConfigSpec.IntValue SPOUT_HEIGHT = BUILDER
            .comment("FPV 输出高度（游戏内修改后下一帧立即生效，无需重启）")
            .defineInRange("spoutHeight", 720, 144, 2160);

    public static final ModConfigSpec.ConfigValue<String> SPOUT_LIBRARY_PATH = BUILDER
            .comment("SpoutLibrary.dll 的绝对路径，留空则自动搜索游戏目录 natives 等位置")
            .define("spoutLibraryPath", "");

    public static final ModConfigSpec SPEC = BUILDER.build();
}
