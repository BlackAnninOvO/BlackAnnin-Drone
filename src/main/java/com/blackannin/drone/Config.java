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

    public static final ModConfigSpec.DoubleValue DRONE_MAX_RADIUS = BUILDER
            .comment("无人机最大活动半径 (默认 5.0)")
            .defineInRange("droneMaxRadius", 5.0, 1.0, 20.0);

    public static final ModConfigSpec.DoubleValue DRONE_SPEED_FACTOR = BUILDER
            .comment("玩家移动速度对无人机速度的影响系数 (默认 2.5，0 = 不受影响)")
            .defineInRange("droneSpeedFactor", 2.5, 0.0, 8.0);

    public static final ModConfigSpec.DoubleValue DRONE_MAX_SPEED = BUILDER
            .comment("无人机最大飞行速度 (默认 3.0，单位: 格/tick)")
            .defineInRange("droneMaxSpeed", 3.0, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue DRONE_TELEPORT_DISTANCE = BUILDER
            .comment("无人机与玩家距离超过此值时强制传送回玩家身边 (默认 16.0，单位: 格)")
            .defineInRange("droneTeleportDistance", 16.0, 4.0, 128.0);

    static final ModConfigSpec SPEC = BUILDER.build();
}
