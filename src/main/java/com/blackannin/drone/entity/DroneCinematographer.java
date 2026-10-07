package com.blackannin.drone.entity;

import com.blackannin.drone.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 自动运镜（电影模式）：无人机化身"自动摄影师"，在环绕、追尾、正前方、侧跟四种机位间
 * 自动轮换，实时跟随运动中的玩家且镜头始终对准玩家（参考影视运镜与开放世界游戏的导演镜头）。
 *
 * <p>防掉队核心——速度前馈：期望速度 = 位置修正项 + 玩家实测速度。
 * 玩家速度取相邻 tick 位移差（服务端 getDeltaMovement 对玩家不可靠，见 AI_PROMPT 附录 A 参考 5），
 * 匀速运动时无人机速度与玩家完全一致，修正项只需弥补机位自身的环绕/弧线运动，
 * 因此玩家速度再快也不会掉队（总上限 4 格/tick ≈ 80 格/秒，覆盖鞘翅烟花）。</p>
 *
 * <p>垂直逻辑与跟随模式一致：复用 {@link DroneNavigator} 的 groundAnchor/isJumpArc 机制，
 * 原地跳跃弧线期间机身高度锁定、速度前馈剥离垂直分量、升降剖面冻结、瞄准高度锁在起跳眼高——
 * 机身与 OBS 画面（镜头俯仰）都不随跳跃起伏；真实升降（爬方块、坠落、飞行）照常跟随。</p>
 *
 * <p>防眩晕与镜头切换：机位方向每 tick 只向目标方向旋转固定上限（镜头切换呈弧线滑入而非
 * 瞬切，也不会横穿玩家视野），朝向与速度均做指数平滑，全程无突变；
 * 各机位自带升降剖面（Crane）：环绕/侧跟在镜头内缓慢升起再降回，追尾稍高、正前方稍低。
 * 视线被长时间阻挡时由实体侧自动退出运镜并恢复跟随。</p>
 */
final class DroneCinematographer {
    /** 机位类型：环绕（ORBIT）/ 追尾（CHASE）/ 正前方（FRONT）/ 侧跟（TRACK），按镜头时长依次轮换 */
    private enum Shot {
        ORBIT, CHASE, FRONT, TRACK
    }

    /** 位置修正增益：每 tick 向机位目标点收敛的比例（玩家位移由速度前馈覆盖，此项只补机位运动） */
    private static final double CORRECTION = 0.35D;
    /** 速度平滑系数：加减速柔和，镜头不顿挫 */
    private static final double VELOCITY_SMOOTHING = 0.45D;
    /** 运镜速度上限（格/tick） */
    private static final double MAX_SPEED = 4.0D;
    /** 玩家速度前馈钳制上限（格/tick），防止传送等瞬时位移甩出镜头 */
    private static final double PLAYER_SPEED_CLAMP = 3.0D;
    /** 朝向平滑系数：低系数保证镜头摇移平缓不晕 */
    private static final float AIM_SMOOTHING = 0.2F;
    /** 机位方向每 tick 最大旋转角（度）：镜头切换/跟转的弧线速率 */
    private static final double MAX_TURN_DEG = 1.6D;
    /** 半径与高度的收敛系数：镜头切换时机位参数平滑过渡 */
    private static final double RADIUS_SMOOTHING = 0.04D;
    private static final double HEIGHT_SMOOTHING = 0.06D;
    /** 玩家水平速度低于该值（格/tick）视为静止：追尾/正前方/侧跟机位改以玩家朝向为基准 */
    private static final double MOVE_DIR_THRESHOLD_SQR = 0.05D * 0.05D;
    /** 连续无视线达到该 tick 数（5 秒）自动退出运镜 */
    private static final int LOS_EXIT_TICKS = 100;
    /** 侧跟机位的方向角（度）：正侧 90° 内收 20°，取 3/4 侧面跟拍构图 */
    private static final double TRACK_ANGLE_DEG = 70.0D;
    /** 正前方机位的偏角（度）：偏离正对方向约 20°，避免呆板的正面直拍 */
    private static final double FRONT_ANGLE_DEG = 20.0D;

    private Shot shot = Shot.ORBIT;
    private int shotTicks;
    /** 机位方向（玩家指向无人机的水平单位向量），切换镜头时限速旋转过渡 */
    private Vec3 offsetDir = new Vec3(0.0D, 0.0D, 1.0D);
    /** 当前水平半径与垂直高度偏移（平滑值，目标值来自配置与机位剖面） */
    private double radius = 5.0D;
    private double heightOffset = 1.5D;
    /** 垂直基准：真实升降时取玩家 Y；跳跃弧线期间锁定在起跳时的高度 */
    private double verticalBaseY;
    /** 侧跟机位的跟随侧（+1 玩家右侧 / -1 左侧），进入镜头时按无人机当前位置选定 */
    private int trackingSide = 1;
    /** 正前方机位的偏角侧（+1/-1），进入镜头时按无人机当前位置选定，偏角朝向就近一侧 */
    private int frontSide = 1;
    /** 最近一次生效的升降目标：跳跃弧线期间冻结，镜头高度完全静止 */
    private double lastHeightGoal = 1.5D;
    /** 玩家速度测量基准位置 */
    private Vec3 lastPlayerPos;
    /** 连续无视线 tick 计数 */
    private int losTicks;
    /** 平滑后的运镜速度 */
    private Vec3 smoothedVelocity = Vec3.ZERO;

    /** 从无人机当前位置无缝切入运镜：机位方向/半径/高度均取当前相对偏移，开启瞬间不跳变 */
    void start(DroneEntity drone, Player owner) {
        Vec3 offset = drone.position().subtract(owner.position());
        Vec3 flat = new Vec3(offset.x, 0.0D, offset.z);
        if (flat.lengthSqr() > 1.0E-4D) {
            this.offsetDir = flat.normalize();
        }
        this.radius = Mth.clamp(flat.length(), 2.0D, Config.DRONE_CINEMA_RADIUS.get());
        this.heightOffset = Mth.clamp(offset.y, 0.0D, Config.DRONE_CINEMA_HEIGHT.get());
        this.verticalBaseY = owner.getY();
        this.lastHeightGoal = this.heightOffset;
        this.shot = Shot.ORBIT;
        this.shotTicks = 0;
        this.losTicks = 0;
        this.lastPlayerPos = owner.position();
    }

    /**
     * 运镜每 tick 推进。
     *
     * @return false = 视线长时间受阻，请求实体退出运镜并恢复跟随
     */
    boolean tick(DroneEntity drone, Player owner) {
        if (this.lastPlayerPos == null) {
            // 读档/跨维度重建后首次运镜：状态缺失，从当前位置重新切入
            start(drone, owner);
        }
        Vec3 playerVel = measurePlayerVelocity(owner);
        Vec3 moveDir = playerMoveDirection(owner, playerVel);

        // 1) 垂直逻辑与跟随模式一致：原地跳跃弧线期间高度锁定，真实升降照常跟随
        boolean ownerOnGround = owner.onGround();
        if (ownerOnGround) {
            drone.navigator.updateGroundAnchor(owner);
        }
        boolean followVertical = ownerOnGround || !drone.navigator.isJumpArc(owner, ownerOnGround);
        if (followVertical) {
            this.verticalBaseY = owner.getY();
        }

        // 2) 机位方向推进：环绕匀速旋转；追尾/正前方/侧跟以限速弧线滑向目标方向
        switch (this.shot) {
            case ORBIT -> this.offsetDir = DroneNavigator.rotateY(this.offsetDir, Config.DRONE_CINEMA_SPEED.get());
            case CHASE -> {
                if (moveDir != null) {
                    // 追尾：机位在玩家运动方向的正后方
                    this.offsetDir = rotateToward(this.offsetDir, moveDir.scale(-1.0D));
                }
            }
            case FRONT -> {
                if (moveDir != null) {
                    // 正前方：机位在玩家运动方向前方并偏约 20°，取 3/4 侧面构图而非完全正面
                    this.offsetDir = rotateToward(this.offsetDir,
                            DroneNavigator.rotateY(moveDir, this.frontSide * FRONT_ANGLE_DEG));
                }
            }
            case TRACK -> {
                if (moveDir != null) {
                    // 侧跟：正侧 90° 内收 20°（70°），3/4 侧面跟拍更有层次
                    Vec3 side = DroneNavigator.rotateY(moveDir, this.trackingSide * TRACK_ANGLE_DEG);
                    this.offsetDir = rotateToward(this.offsetDir, side);
                }
            }
        }

        // 3) 机位参数平滑：半径/高度向各自目标收敛（镜头切换时平滑过渡，不跳变）。
        //    跳跃弧线期间升降目标一并冻结（Crane 暂停），配合锁定的垂直基准与无垂直前馈，
        //    镜头高度完全静止，不随跳跃起伏
        double goalRadius = this.shot == Shot.CHASE
                ? Config.DRONE_CINEMA_RADIUS.get() + 1.0D
                : Config.DRONE_CINEMA_RADIUS.get();
        this.radius += (goalRadius - this.radius) * RADIUS_SMOOTHING;
        if (followVertical) {
            this.lastHeightGoal = heightGoal(this.shotTicks);
        }
        this.heightOffset += (this.lastHeightGoal - this.heightOffset) * HEIGHT_SMOOTHING;

        // 4) 期望速度 = 位置修正 + 玩家速度前馈（匀速运动时前馈完全覆盖玩家位移，不掉队）。
        //    跳跃弧线期间剥离前馈的垂直分量：位置项已把高度锁在起跳基准，前馈若保留 Y
        //    会把镜头随跳跃带起（表现为颠簸）
        Vec3 target = new Vec3(
                owner.getX() + this.offsetDir.x * this.radius,
                this.verticalBaseY + this.heightOffset,
                owner.getZ() + this.offsetDir.z * this.radius);
        Vec3 feed = followVertical ? playerVel : new Vec3(playerVel.x, 0.0D, playerVel.z);
        Vec3 desired = target.subtract(drone.position()).scale(CORRECTION).add(feed);
        if (desired.lengthSqr() > MAX_SPEED * MAX_SPEED) {
            desired = desired.normalize().scale(MAX_SPEED);
        }
        this.smoothedVelocity = this.smoothedVelocity
                .add(desired.subtract(this.smoothedVelocity).scale(VELOCITY_SMOOTHING));
        if (!drone.navigator.moveWithSlide(this.smoothedVelocity)) {
            // 全方向受阻：清零速度，避免贴墙时持续积累
            this.smoothedVelocity = Vec3.ZERO;
        }

        // 5) 镜头实时对准玩家（指数平滑，摇移平缓）；瞄准高度带跳跃过滤，
        //    否则玩家一跳镜头俯仰就跟着抬落，表现为 OBS 画面的轻微起伏
        aimAt(drone, owner, followVertical);

        // 6) 镜头轮换与视线保护
        if (++this.shotTicks >= Config.DRONE_CINEMA_SHOT_SECONDS.get() * 20) {
            this.shotTicks = 0;
            advanceShot(owner);
        }
        if (drone.navigator.hasLineOfSight(owner.getEyePosition())) {
            this.losTicks = 0;
        } else if (++this.losTicks >= LOS_EXIT_TICKS) {
            return false;
        }
        return true;
    }

    /**
     * 机位升降剖面（Crane）：环绕/侧跟在镜头内缓慢升起再降回（半个正弦），
     * 追尾稍高便于越过地形，正前方稍低形成仰拍。全部叠加在垂直基准之上。
     */
    private double heightGoal(int ticks) {
        double configHeight = Config.DRONE_CINEMA_HEIGHT.get();
        double duration = Config.DRONE_CINEMA_SHOT_SECONDS.get() * 20;
        double progress = Mth.clamp(ticks / duration, 0.0D, 1.0D);
        double crane = Math.sin(Math.PI * progress) * configHeight;
        return switch (this.shot) {
            case ORBIT -> configHeight + crane;
            case CHASE -> configHeight + 0.5D;
            case FRONT -> Math.max(0.4D, configHeight - 0.6D);
            case TRACK -> configHeight + crane * 0.6D;
        };
    }

    /**
     * 镜头平滑对准玩家（偏航与俯仰分别指数平滑）。
     *
     * <p>瞄准高度带跳跃过滤：跳跃弧线期间锁定在"起跳基准 + 眼高"，镜头俯仰不随跳跃
     * 抬落（这正是 OBS 画面起伏的来源——机身锁定后，只有瞄准目标仍在动）；
     * 真实升降（爬方块、坠落、飞行）照常跟踪。</p>
     */
    private void aimAt(DroneEntity drone, Player owner, boolean followVertical) {
        double aimY = followVertical ? owner.getEyeY() : this.verticalBaseY + owner.getEyeHeight();
        Vec3 aim = new Vec3(owner.getX(), aimY, owner.getZ()).subtract(drone.position());
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float targetPitch = horizontal < 1.0E-4D ? 0.0F
                : (float) -Math.toDegrees(Math.atan2(aim.y, horizontal));
        float yaw = drone.getYRot() + Mth.degreesDifference(drone.getYRot(), targetYaw) * AIM_SMOOTHING;
        float pitch = drone.getXRot() + (targetPitch - drone.getXRot()) * AIM_SMOOTHING;
        drone.setYRot(yaw);
        drone.setYHeadRot(yaw);
        drone.setYBodyRot(yaw);
        drone.setXRot(pitch);
    }

    /** 镜头轮换：环绕 → 追尾 → 正前方 → 侧跟 → 环绕；侧跟方向按无人机当前所在一侧选定，切入几乎不转向 */
    private void advanceShot(Player owner) {
        this.shot = switch (this.shot) {
            case ORBIT -> Shot.CHASE;
            case CHASE -> Shot.FRONT;
            case FRONT -> Shot.TRACK;
            case TRACK -> Shot.ORBIT;
        };
        if (this.shot == Shot.TRACK || this.shot == Shot.FRONT) {
            // 偏角侧按无人机当前所在一侧选定（叉积符号），镜头切入时几乎无需转向
            Vec3 moveDir = playerMoveDirection(owner, measurePlayerVelocity(owner));
            if (moveDir != null) {
                double cross = moveDir.x * this.offsetDir.z - moveDir.z * this.offsetDir.x;
                if (this.shot == Shot.TRACK) {
                    this.trackingSide = cross >= 0.0D ? 1 : -1;
                } else {
                    this.frontSide = cross >= 0.0D ? 1 : -1;
                }
            }
        }
    }

    /** 玩家实测速度（相邻 tick 位移差），钳制上限防止传送等瞬时位移甩出镜头 */
    private Vec3 measurePlayerVelocity(Player owner) {
        Vec3 pos = owner.position();
        Vec3 vel = this.lastPlayerPos == null ? Vec3.ZERO : pos.subtract(this.lastPlayerPos);
        this.lastPlayerPos = pos;
        if (vel.lengthSqr() > PLAYER_SPEED_CLAMP * PLAYER_SPEED_CLAMP) {
            vel = vel.normalize().scale(PLAYER_SPEED_CLAMP);
        }
        return vel;
    }

    /** 玩家水平运动方向；速度过低（静止）时以玩家朝向为基准，朝向也退化时返回 null（机位保持不变） */
    private Vec3 playerMoveDirection(Player owner, Vec3 playerVel) {
        Vec3 flat = new Vec3(playerVel.x, 0.0D, playerVel.z);
        if (flat.lengthSqr() >= MOVE_DIR_THRESHOLD_SQR) {
            return flat.normalize();
        }
        Vec3 look = owner.getLookAngle();
        Vec3 lookFlat = new Vec3(look.x, 0.0D, look.z);
        return lookFlat.lengthSqr() < 1.0E-4D ? null : lookFlat.normalize();
    }

    /**
     * 将当前机位方向向目标方向旋转，每 tick 转角不超过 MAX_TURN_DEG。
     * 切换镜头时无人机沿弧线滑入新机位，而非直线穿过玩家视野。
     */
    private Vec3 rotateToward(Vec3 current, Vec3 goal) {
        double dot = Mth.clamp(current.x * goal.x + current.z * goal.z, -1.0D, 1.0D);
        double cross = current.x * goal.z - current.z * goal.x;
        double signed = Math.toDegrees(Math.atan2(cross, dot));
        if (Math.abs(signed) <= MAX_TURN_DEG) {
            return goal;
        }
        return DroneNavigator.rotateY(current, signed >= 0.0D ? MAX_TURN_DEG : -MAX_TURN_DEG);
    }
}
