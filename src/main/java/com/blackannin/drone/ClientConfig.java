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
            .defineInRange("spoutWidth", 1920, 256, 3840);

    public static final ModConfigSpec.IntValue SPOUT_HEIGHT = BUILDER
            .comment("FPV 输出高度（游戏内修改后下一帧立即生效，无需重启）")
            .defineInRange("spoutHeight", 1080, 144, 2160);

    public static final ModConfigSpec.DoubleValue DRONE_FOV = BUILDER
            .comment("无人机视角的视场角（默认 70 = 我的世界默认值，不随玩家设置变化）")
            .defineInRange("droneFov", 70.0, 30.0, 110.0);

    public static final ModConfigSpec.BooleanValue STICK_VIEW_MODE = BUILDER
            .comment("手动操纵的默认视角：true = 摇杆（手柄）渐进控制，false = 鼠标直接控制。")
            .comment("操控台内按 V 或点按钮切换后会自动保存，下次进入沿用上次选择。")
            .define("stickViewMode", true);

    public static final ModConfigSpec.IntValue SPOUT_EVERY_N_FRAMES = BUILDER
            .comment("推流帧率阀门：每 N 帧渲染并推送一帧无人机画面（1=每帧，2=30fps，3=20fps）。")
            .comment("开启光影时无人机画面需要第二个完整渲染管线，建议设为 2 以减半性能开销；OBS 侧会保持上一帧画面。")
            .defineInRange("spoutEveryNFrames", 1, 1, 10);

    public static final ModConfigSpec.BooleanValue SPOUT_KEEP_ASPECT = BUILDER
            .comment("保持游戏窗口宽高比：窗口不是输出分辨率的比例时，画面按比例居中缩放，")
            .comment("四周留出【全透明】区域（而不是拉伸变形或填充黑边），便于在 OBS 中叠加合成。")
            .define("spoutKeepAspect", true);

    public static final ModConfigSpec.BooleanValue SPOUT_OSD = BUILDER
            .comment("推流画面叠加层（OSD）：在发给 OBS 的画面上叠加 REC 指示、中心准星与遥测条，")
            .comment("仅存在于推流帧中，玩家自己的画面完全不受影响；修改后下一帧立即生效。")
            .define("spoutOsd", true);

    public static final ModConfigSpec.BooleanValue SPOUT_OSD_PRO = BUILDER
            .comment("OSD 显示模式：false = CLEAN 纯净（底部遥测条 + 小准星），true = PRO 专业（航向刻度带、")
            .comment("高度刻度尺、人工地平线、Home 指示、参数分布全屏 + 加粗放大十字准星）。修改后下一帧立即生效。")
            .define("spoutOsdPro", false);

    public static final ModConfigSpec.BooleanValue SPOUT_DEBUG_DUMP = BUILDER
            .comment("排障用：每次开始推流时把实际发送的画面导出为游戏目录下的 drone_stream_debug.png")
            .define("spoutDebugDump", false);

    public static final ModConfigSpec.ConfigValue<String> SPOUT_LIBRARY_PATH = BUILDER
            .comment("SpoutLibrary.dll 的绝对路径，留空则自动搜索游戏目录 natives 等位置")
            .define("spoutLibraryPath", "");

    public static final ModConfigSpec SPEC = BUILDER.build();
}
