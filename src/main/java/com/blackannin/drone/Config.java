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

    static final ModConfigSpec SPEC = BUILDER.build();
}
