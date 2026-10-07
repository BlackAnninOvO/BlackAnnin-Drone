package com.blackannin.drone;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_DISTANCE = BUILDER
            .comment("无人机跟随玩家的距离 (默认 3.0)")
            .defineInRange("droneFollowDistance", 3.0, 1.0, 10.0);

    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_HEIGHT = BUILDER
            .comment("无人机跟随玩家的高度 (默认 1.0)")
            .defineInRange("droneFollowHeight", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.IntValue DRONE_TAKEOFF_DELAY = BUILDER
            .comment("无人机放置后的起飞延迟 (单位: tick, 20 ticks = 1秒)")
            .defineInRange("droneTakeoffDelay", 40, 0, 200);

    public static final ModConfigSpec.DoubleValue DRONE_SPEED_FACTOR = BUILDER
            .comment("玩家移动速度对无人机速度的影响系数 (默认 2.5，0 = 不受影响)")
            .defineInRange("droneSpeedFactor", 2.5, 0.0, 8.0);

    public static final ModConfigSpec.DoubleValue DRONE_MAX_SPEED = BUILDER
            .comment("无人机常规跟随的最大飞行速度 (默认 3.0，单位: 格/tick)；")
            .comment("玩家高速移动（坠落/鞘翅/创造或模组飞行）时无人机会自动超出该上限以实时紧跟")
            .defineInRange("droneMaxSpeed", 3.0, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue DRONE_MANUAL_SPEED = BUILDER
            .comment("手动操控模式的飞行速度上限 (默认 0.5，可调范围 0.1~2.0，单位: 格/tick)")
            .defineInRange("droneManualSpeed", 0.5, 0.1, 2.0);

    public static final ModConfigSpec.DoubleValue DRONE_MANUAL_RADIUS = BUILDER
            .comment("手动操控模式的活动半径 (默认 64.0，上限 128.0；独立于强制传送距离，不受其影响)")
            .defineInRange("droneManualRadius", 64.0, 4.0, 128.0);

    public static final ModConfigSpec.DoubleValue DRONE_TELEPORT_DISTANCE = BUILDER
            .comment("无人机与玩家距离超过此值时强制传送回玩家身边 (默认 16.0，单位: 格)")
            .defineInRange("droneTeleportDistance", 16.0, 4.0, 128.0);

    public static final ModConfigSpec.DoubleValue DRONE_CINEMA_RADIUS = BUILDER
            .comment("自动运镜（环绕机位）的环绕半径 (默认 5.0 格；追尾/侧跟机位距离随之联动)")
            .defineInRange("droneCinemaRadius", 5.0, 3.0, 12.0);

    public static final ModConfigSpec.DoubleValue DRONE_CINEMA_SPEED = BUILDER
            .comment("自动运镜（环绕机位）的环绕角速度 (默认 2.5 度/tick ≈ 9.6 秒一周，越大越快)")
            .defineInRange("droneCinemaSpeed", 2.5, 0.5, 4.0);

    public static final ModConfigSpec.DoubleValue DRONE_CINEMA_HEIGHT = BUILDER
            .comment("自动运镜的相机相对玩家高度 (默认 1.5 格)")
            .defineInRange("droneCinemaHeight", 1.5, 0.0, 8.0);

    public static final ModConfigSpec.IntValue DRONE_CINEMA_SHOT_SECONDS = BUILDER
            .comment("自动运镜每个镜头的持续时长 (默认 10 秒；环绕→追尾→侧跟依次轮换)")
            .defineInRange("droneCinemaShotSeconds", 10, 4, 30);

    static final ModConfigSpec SPEC = BUILDER.build();
}
