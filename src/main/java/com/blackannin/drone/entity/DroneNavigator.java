package com.blackannin.drone.entity;

import com.blackannin.drone.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 无人机飞行导航器：负责目标平滑、全局寻路（A*）、局部绕障、卡住脱困与传送兜底。
 * 与实体状态（同步数据、归属、交互、持久化）解耦，便于单独维护与测试。
 */
final class DroneNavigator {
    /** 基础飞行速度（格/tick），玩家静止时的速度 */
    private static final double FLY_SPEED = 0.4D;
    /** 跟随目标平滑系数（越小越平缓，避免视角抖动） */
    private static final double HORIZONTAL_SMOOTHING = 0.25D;
    private static final double VERTICAL_SMOOTHING = 0.4D;
    /** 跳跃弧线判定滞回：进入阈值（格） */
    private static final double JUMP_ARC_ENTER_HEIGHT = 1.4D;
    /** 跳跃弧线判定滞回：退出阈值（格）——单阈值会在飞行时每 tick 翻转，造成垂直抖动 */
    private static final double JUMP_ARC_EXIT_HEIGHT = 1.8D;
    /** 弧线最短锁定时长（tick）：进入后至少保持，防单 tick 翻转 */
    private static final int JUMP_ARC_MIN_TICKS = 4;
    /** 速度平滑系数：让移动加减速更柔和 */
    private static final double VELOCITY_SMOOTHING = 0.35D;

    /** 绕障方向记忆时长（tick）：记住当前绕行方向，避免在障碍边缘来回抖动 */
    private static final int DETOUR_MEMORY_TICKS = 20;
    /** 绕障候选方向的角度（度）：15° 步长，保证能对准 1 格宽的门洞/缺口 */
    private static final double[] DETOUR_ANGLES = {
            0.0D, 15.0D, -15.0D, 30.0D, -30.0D, 45.0D, -45.0D, 60.0D, -60.0D,
            75.0D, -75.0D, 90.0D, -90.0D, 105.0D, -105.0D, 120.0D, -120.0D,
            135.0D, -135.0D, 150.0D, -150.0D, 165.0D, -165.0D, 180.0D};
    /** 绕障单步位移：慢速时不超过 1 格保精度；高速时按速度的 90% 跟进（否则永远追不上坠落/飞行） */
    private static final double DETOUR_STEP_MAX = 1.0D;
    /** 沿用上次绕行方向的评分加成，避免在障碍边缘来回抖动 */
    private static final double DETOUR_MEMORY_BONUS = 0.75D;

    /** 重新搜索全局路径的间隔（tick） */
    private static final int REPATH_INTERVAL_TICKS = 10;
    /** 目标移动超过该距离（格）时提前重新寻路 */
    private static final double REPATH_GOAL_MOVE_SQR = 4.0D;

    /** 寻路进度检测窗口（tick）：窗口内净位移过小视为原地打转 */
    private static final int STUCK_WINDOW_TICKS = 20;
    /** 窗口内净位移小于该值（格）且离目标仍远 → 判定卡住 */
    private static final double STUCK_NET_DISPLACEMENT = 4.0D;
    /** 距目标小于该值时不判定卡住：正常跟随/悬停时本就在玩家附近，
     *  取 6 格可避免玩家缓慢移动（潜行等）被误判为卡住 */
    private static final double STUCK_MIN_TARGET_DISTANCE = 6.0D;
    /** 连续改道次数上限，超过则直接传送 */
    private static final int MAX_REROUTE_ATTEMPTS = 1;

    /** 手动模式鼠标灵敏度：界面像素 → 角度 */
    private static final float MANUAL_YAW_SENS = 0.45F;
    private static final float MANUAL_PITCH_SENS = 0.35F;
    /** 判定盒收窄量：贴墙接触不算碰撞 */
    private static final double BOX_SHRINK = 0.05D;
    /** 玩家实测速度的钳制上限（格/tick），防止传送等瞬时位移产生尖峰 */
    private static final double OWNER_SPEED_CLAMP = 4.0D;

    private final DroneEntity drone;

    /** 平滑后的跟随目标 */
    private Vec3 smoothedTarget;
    /** 平滑后的速度 */
    private Vec3 smoothedVelocity = Vec3.ZERO;
    /** 玩家上次落地时的位置，用于识别原地跳跃 */
    private Vec3 groundAnchor;
    /** 当前绕行方向（相对目标方向的偏角，度）与剩余记忆 tick */
    private double detourAngle;
    private int detourTicks;
    /** 当前绕行侧（+1 左 / -1 右 / 0 未定），固定一侧避免左右反复切换导致打转 */
    private int wallFollowSide;
    /** 全局路径路点与当前索引、重新寻路计时 */
    private List<Vec3> path = List.of();
    private int pathIndex;
    private int repathTicks;
    private Vec3 pathGoal;
    /** 寻路进度检测（窗口起点、计时、连续改道次数） */
    private Vec3 progressAnchor;
    private int progressTicks;
    private int rerouteAttempts;
    /** 玩家实测速度（相邻 tick 位移差）：服务端 getDeltaMovement 对玩家不可靠（附录 A 参考 5） */
    private Vec3 lastOwnerPos;
    private Vec3 ownerVelocity = Vec3.ZERO;
    /** 跳跃弧线冷却（tick）：弧线期间与其后若干 tick 抑制垂直前馈（见 verticalFeedAllowed） */
    private int jumpArcCooldown;
    private static final int JUMP_ARC_FEED_COOLDOWN = 4;
    /** 视线连续受阻 tick 数：连续受阻才转绕障（滞回，防走动时视线闪烁引发模式交替） */
    private int losBlockedTicks;
    /** 视线转绕障的滞回阈值（tick） */
    private static final int LOS_DETOUR_DELAY_TICKS = 3;
    /** 前馈用垂直速度（低通 EMA 0.5）：压掉走台阶/落地瞬间的 1-tick 尖峰，只保留持续升降 */
    private double verticalFeedY;
    /** 跳跃弧线状态（带滞回与最短时长，防边界高频翻转）与其持续 tick 数 */
    private boolean inJumpArc;
    private int inJumpArcTicks;
    /** 状态推进去重：isJumpArc 每 tick 可能被多处调用（followHeight 与前馈许可），
     *  用游戏刻号保证状态机每 tick 只推进一次 */
    private long jumpArcLastTick = -1L;

    DroneNavigator(DroneEntity drone) {
        this.drone = drone;
    }

    /** 每 tick 递减绕行记忆 */
    void tick() {
        if (this.detourTicks > 0) {
            this.detourTicks--;
        }
    }

    /** 记录玩家落地点，用于识别"原地跳跃" */
    void updateGroundAnchor(Player owner) {
        this.groundAnchor = owner.position();
    }

    /** 传送或换维度后重置导航状态 */
    void reset() {
        this.smoothedTarget = null;
        this.smoothedVelocity = Vec3.ZERO;
        this.groundAnchor = null;
        this.detourTicks = 0;
        this.wallFollowSide = 0;
        this.progressAnchor = null;
        this.rerouteAttempts = 0;
        this.lastOwnerPos = null;
        this.ownerVelocity = Vec3.ZERO;
        this.jumpArcCooldown = 0;
        this.losBlockedTicks = 0;
        this.verticalFeedY = 0.0D;
        this.inJumpArc = false;
        this.inJumpArcTicks = 0;
        this.jumpArcLastTick = -1L;
        this.path = List.of();
        this.pathIndex = 0;
    }

    /**
     * 目标点平滑：水平缓动跟随。
     * 垂直方向以玩家是否落地区分：落地时始终平滑跟随其高度（跑酷逐格上升也能跟上）；
     * 玩家在空中时只有大幅升降（飞行、坠落）才跟随，跳跃幅度被忽略，避免视角上下浮动。
     */
    Vec3 smoothed(Vec3 raw, boolean followHeight) {
        if (this.smoothedTarget == null) {
            this.smoothedTarget = raw;
            return raw;
        }
        Vec3 current = this.smoothedTarget;
        double dy = raw.y - current.y;
        // 垂直平滑三级加速：大幅升降（坠落、鞘翅俯冲、快速爬升）时快速跟上，避免镜头滞后掉队
        double absDy = Math.abs(dy);
        double factor = VERTICAL_SMOOTHING;
        if (absDy > 6.0D) {
            factor = 0.9D;
        } else if (absDy > 3.0D) {
            factor = 0.75D;
        } else if (absDy > 1.5D) {
            factor = 0.55D;
        }
        // 水平平滑随玩家速度自适应加速：高速飞行下若仍用 0.25，平滑目标会滞后约 3×速度，
        // 造成持续掉队并周期性触发传送（表现为一顿一顿）；常速行走不受影响
        double playerSpeed = this.ownerVelocity.length();
        double hFactor = HORIZONTAL_SMOOTHING;
        if (playerSpeed > 0.35D) {
            hFactor = Math.min(0.95D, HORIZONTAL_SMOOTHING + (playerSpeed - 0.35D) * 0.4D);
        }
        double newY = followHeight ? current.y + dy * factor : current.y;
        this.smoothedTarget = new Vec3(
                current.x + (raw.x - current.x) * hFactor,
                newY,
                current.z + (raw.z - current.z) * hFactor);
        return this.smoothedTarget;
    }

    /**
     * 玩家是否处于"跳跃弧线"中：在空中、非飞行（创造飞行与鞘翅均排除）、
     * 且相对上次落地点的高度变化处于跳跃幅度内。
     *
     * <p>带滞回与最短时长：单阈值会在飞行/低空移动时于边界处每 tick 翻转，
     * 使 followHeight 与前馈许可高频切换、无人机垂直位置抖动（空中严重抽搐的根因）。
     * 进入阈值 1.4 格、退出阈值 1.8 格、进入后至少保持 4 tick。</p>
     */
    boolean isJumpArc(Player owner, boolean ownerOnGround) {
        if (ownerOnGround || this.groundAnchor == null
                || owner.getAbilities().flying || owner.isFallFlying()) {
            this.inJumpArc = false;
            this.inJumpArcTicks = 0;
            return false;
        }
        // 每 tick 只推进一次状态（本方法每 tick 可能被多处调用，去重防双倍推进）
        long now = owner.level().getGameTime();
        if (now != this.jumpArcLastTick) {
            this.jumpArcLastTick = now;
            double dy = Math.abs(owner.getY() - this.groundAnchor.y);
            if (this.inJumpArc) {
                this.inJumpArcTicks++;
                if (dy > JUMP_ARC_EXIT_HEIGHT && this.inJumpArcTicks >= JUMP_ARC_MIN_TICKS) {
                    this.inJumpArc = false;
                }
            } else if (dy < JUMP_ARC_ENTER_HEIGHT) {
                this.inJumpArc = true;
                this.inJumpArcTicks = 0;
            }
        }
        return this.inJumpArc;
    }

    /**
     * 全局路径跟随：视线被挡或正穿过强开的门时用 A* 搜索可通行路径并按路点前进；
     * 视线通畅时一律直飞（高速飞行平滑不顿挫）。局部转向（视线/绕障）无法定位
     * "封闭房间的小洞口"这类入口，必须靠全局搜索。
     */
    Vec3 routeAlongPath(Vec3 goal, Player owner) {
        // 门被强开期间必须沿 A* 路点走：此时视线已通，直飞会对准玩家身后的跟随点而撞上门框，
        // 只有 A* 的路点是对准门洞中心的（修复"打开铁门却不进去"）
        boolean blocked = !hasLineOfSight(goal) || drone.hasForcedOpenDoors();
        if (!blocked) {
            // 视线通畅一律直飞：高速飞行（鞘翅等）时距离常超 6 格，若转入寻路会因
            // 折线路点与周期性重寻路产生顿挫；直飞路径的视线已验证，安全且平滑
            this.path = List.of();
            return goal;
        }
        // 寻路终点取玩家自身位置：跟随点可能落在墙体/天花板内（不可通行），会导致搜索失败
        Vec3 searchGoal = owner.position();
        // 目标移动超阈值需重寻路，但限频（每 2 tick 最多一次）：高速移动时目标每 tick 都在动，
        // 不限频会每 tick 触发一次 A*（服务端卡顿源）；既有路径晚 1-2 tick 跟进即可
        boolean goalMoved = this.pathGoal != null
                && this.pathGoal.distanceToSqr(searchGoal) > REPATH_GOAL_MOVE_SQR;
        boolean needRepath = this.path.isEmpty()
                || this.pathIndex >= this.path.size()
                || --this.repathTicks <= 0
                || this.pathGoal == null
                || (goalMoved && this.repathTicks <= REPATH_INTERVAL_TICKS - 2);
        if (needRepath) {
            this.path = DronePathfinder.findPath(drone.level(), drone, searchGoal);
            this.pathGoal = searchGoal;
            this.pathIndex = 0;
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
        if (this.path.isEmpty()) {
            return goal;
        }
        while (this.pathIndex < this.path.size()
                && drone.position().distanceTo(this.path.get(this.pathIndex)) < 1.0D) {
            this.pathIndex++;
        }
        return this.pathIndex < this.path.size() ? this.path.get(this.pathIndex) : goal;
    }

    /**
     * 朝目标飞行：视线畅通（或短暂闪烁）优先滑行直飞；视线连续受阻才交给绕障；
     * 绕障也失败则传送兜底。
     *
     * <p>滞回与滑行的组合消除了"贴地形移动时的严重抽搐"：曾出现直飞受阻→绕障选侧→
     * 直飞又成功（清零侧向记忆）→再受阻重新选侧（可能换边）的每 tick 交替极限环，
     * 表现为无人机左右横跳。现在部分受阻全部由 moveWithSlide 滑行消化，
     * 只有连续受阻才进入绕障模式。</p>
     */
    void flyToward(Vec3 target, Player owner, boolean followHeight) {
        drone.getNavigation().stop();

        boolean verticalFeed = verticalFeedAllowed(owner, followHeight);
        boolean los = hasLineOfSight(target);
        if (los) {
            this.losBlockedTicks = 0;
        } else if (this.losBlockedTicks < LOS_DETOUR_DELAY_TICKS) {
            this.losBlockedTicks++;
        }
        boolean preferDirect = los || this.losBlockedTicks < LOS_DETOUR_DELAY_TICKS;
        if (preferDirect && flyStraight(target, owner, verticalFeed)) {
            this.wallFollowSide = 0;
            this.detourTicks = 0;
            // 滑行贴墙原地磨时净位移同样很小，卡住检测在此保留
            checkProgress(target, owner);
            return;
        }
        double speed = speedForOwner();
        if (tryDetour(target, speed)) {
            checkProgress(target, owner);
            return;
        }
        drone.teleportToOwner(owner);
    }

    /**
     * 垂直前馈许可：跳跃弧线期间与其后 4 tick 冷却期内抑制。
     * 连跳落地那一 tick 玩家在地面（followHeight=true）但实测垂直速度仍是上一跳的下降值，
     * 若被前馈采用，无人机每次落地都会被向下压一下、起跳又弹回——表现为抽搐。
     * 真正的高空坠落/爬升高差 >1.5 格，从不触发弧线判定，前馈不受影响。
     */
    private boolean verticalFeedAllowed(Player owner, boolean followHeight) {
        if (isJumpArc(owner, owner.onGround())) {
            this.jumpArcCooldown = JUMP_ARC_FEED_COOLDOWN;
            return false;
        }
        if (this.jumpArcCooldown > 0) {
            this.jumpArcCooldown--;
            return false;
        }
        return followHeight;
    }

    /**
     * 手动连续飞行（v0.5.0）：键盘三轴连续输入 + 鼠标视角，
     * 像真实无人机一样丝滑移动；撞墙时按轴分解滑行，活动半径由配置限制。
     */
    void flyManual(Player owner, float forward, float strafe, float up, float yawDelta, float pitchDelta) {
        // 视角：鼠标增量直接驱动偏航/俯仰（带平滑）
        float yaw = drone.getYRot() + yawDelta * MANUAL_YAW_SENS;
        float pitch = Mth.clamp(drone.getXRot() + pitchDelta * MANUAL_PITCH_SENS, -89.0F, 89.0F);
        drone.setYRot(yaw);
        drone.setYHeadRot(yaw);
        drone.setYBodyRot(yaw);
        drone.setXRot(pitch);

        double maxSpeed = Config.DRONE_MANUAL_SPEED.get();
        Vec3 look = Vec3.directionFromRotation(0.0F, yaw);
        // 朝向前方的右手侧（地图上顺时针 90°）
        Vec3 right = new Vec3(-look.z, 0.0D, look.x);
        Vec3 desired = new Vec3(
                look.x * forward + right.x * strafe,
                up,
                look.z * forward + right.z * strafe);
        if (desired.lengthSqr() > 1.0D) {
            desired = desired.normalize();
        }
        desired = desired.scale(maxSpeed);
        this.smoothedVelocity = this.smoothedVelocity.add(desired.subtract(this.smoothedVelocity).scale(VELOCITY_SMOOTHING));

        Vec3 step = this.smoothedVelocity;
        if (!moveWithSlide(step)) {
            // 全方向受阻：清零速度，避免贴墙时持续积累
            this.smoothedVelocity = Vec3.ZERO;
        }
        clampToOwnerRadius(owner, Config.DRONE_MANUAL_RADIUS.get());
    }

    /**
     * 受控移动一步（手动模式与运镜共用）：整步优先，受阻时按轴分解滑行，
     * 保持贴墙移动的丝滑感。
     *
     * @return 是否产生了位移（全方向受阻返回 false）
     */
    boolean moveWithSlide(Vec3 step) {
        if (isPassable(step)) {
            doMove(step);
            return true;
        }
        Vec3 sx = new Vec3(step.x, 0.0D, 0.0D);
        Vec3 sy = new Vec3(0.0D, step.y, 0.0D);
        Vec3 sz = new Vec3(0.0D, 0.0D, step.z);
        boolean moved = false;
        if (step.x != 0.0D && isPassable(sx)) { doMove(sx); moved = true; }
        if (step.z != 0.0D && isPassable(sz)) { doMove(sz); moved = true; }
        if (step.y != 0.0D && isPassable(sy)) { doMove(sy); moved = true; }
        return moved;
    }

    /** 手动活动半径限制：越界位置拉回圆形/高度范围（向内移动必然可通行） */
    private void clampToOwnerRadius(Player owner, double max) {
        Vec3 ownerPos = owner.position();
        Vec3 pos = drone.position();
        Vec3 offset = pos.subtract(ownerPos);
        Vec3 horizontal = new Vec3(offset.x, 0.0D, offset.z);
        double dist = horizontal.length();
        Vec3 target = pos;
        if (dist > max && dist > 1.0E-4D) {
            horizontal = horizontal.scale(max / dist);
            target = ownerPos.add(horizontal.x, 0.0D, horizontal.z).add(0.0D, offset.y, 0.0D);
        }
        double minY = 0.2D;
        double maxY = Math.max(Config.DRONE_FOLLOW_HEIGHT.get() + 4.0D, max);
        double y = Mth.clamp(offset.y, minY, maxY);
        if (offset.y != y) {
            target = ownerPos.add(target.x - ownerPos.x - offset.x, y, target.z - ownerPos.z - offset.z).add(offset.x, 0.0D, offset.z);
            target = new Vec3(target.x, ownerPos.y + y, target.z);
        }
        Vec3 correction = target.subtract(pos);
        if (correction.lengthSqr() > 1.0E-8D && drone.level().noCollision(drone, drone.getBoundingBox().move(correction))) {
            doMove(correction);
        }
    }

    /** 朝目标直飞：带速度平滑；返回是否成功移动一步 */
    private boolean flyStraight(Vec3 target, Player owner, boolean verticalFeed) {
        Vec3 delta = target.subtract(drone.position());
        double dist = delta.length();
        if (dist < 0.05D) {
            drone.setDeltaMovement(Vec3.ZERO);
            return true;
        }
        double speed = speedForOwner();
        Vec3 desired = delta.scale(Math.min(1.0D, speed / dist));
        // 大幅升降速度前馈：在位置修正之外叠加玩家实测垂直速度（低通后的值，
        // 台阶/落地瞬间的 1-tick 尖峰已被压掉）——比例追踪对匀速坠落/爬升存在固有
        // 稳态滞后，前馈使无人机以与玩家相同的垂直速度同降/同升（≈零滞后）。
        // 许可条件见 verticalFeedAllowed：跳跃弧线与冷却期内不加（否则连跳会抽搐）
        if (verticalFeed) {
            desired = desired.add(0.0D, this.verticalFeedY, 0.0D);
        }
        this.smoothedVelocity = this.smoothedVelocity.add(desired.subtract(this.smoothedVelocity).scale(VELOCITY_SMOOTHING));
        if (this.smoothedVelocity.lengthSqr() > speed * speed) {
            this.smoothedVelocity = this.smoothedVelocity.normalize().scale(speed);
        }
        // 受阻时按轴分解滑行（与手动模式同一套）：贴地形/墙边的部分受阻平滑通过，
        // 不再掉入绕障模式；仅当所有方向都被堵死（返回 false）才交给 detour 选侧绕行
        Vec3 step = this.smoothedVelocity;
        if (!moveWithSlide(step)) {
            this.smoothedVelocity = Vec3.ZERO;
            return false;
        }
        return true;
    }

    /** 与目标之间是否有通畅的视线（只判实心方块，放行水、打开的门/活板门等） */
    boolean hasLineOfSight(Vec3 target) {
        Vec3 from = new Vec3(drone.getX(), drone.getY() + drone.getBbHeight() * 0.5D, drone.getZ());
        HitResult hit = drone.level().clip(new ClipContext(from, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * 寻路进度检测：窗口内净位移过小（原地打转）且离玩家仍远 → 先换边改道，
     * 连续改道仍无进展则立即传送。可通行方向一直存在但净位移为零时，
     * 仅靠碰撞检测无法发现"卡住"，必须用位移进度判断。
     */
    private void checkProgress(Vec3 target, Player owner) {
        if (this.progressAnchor == null) {
            this.progressAnchor = drone.position();
            this.progressTicks = 0;
            return;
        }
        if (++this.progressTicks < STUCK_WINDOW_TICKS) {
            return;
        }
        double netDisplacement = drone.position().distanceTo(this.progressAnchor);
        double targetDistance = drone.position().distanceTo(target);
        this.progressAnchor = drone.position();
        this.progressTicks = 0;
        // 只有"离目标近 且 看得见玩家"才算正常跟随/悬停；
        // 被薄墙、铁门这类挡在很近处时同样要脱困，否则会一直卡在门前；
        // 有被强开的门时同理：门口视线虽通，但可能正贴着门框，仍需换侧/传送兜底
        boolean hoveringNearby = targetDistance < STUCK_MIN_TARGET_DISTANCE && hasLineOfSight(target)
                && !drone.hasForcedOpenDoors();
        if (hoveringNearby || netDisplacement >= STUCK_NET_DISPLACEMENT) {
            this.rerouteAttempts = 0;
            return;
        }
        if (this.rerouteAttempts >= MAX_REROUTE_ATTEMPTS) {
            this.rerouteAttempts = 0;
            drone.teleportToOwner(owner);
            return;
        }
        // 换边改道：强制走另一侧
        this.rerouteAttempts++;
        this.wallFollowSide = -this.wallFollowSide;
        this.detourAngle = -this.detourAngle;
        this.detourTicks = DETOUR_MEMORY_TICKS;
    }

    /** 每 tick 实测玩家速度（相邻 tick 位移差，钳制防传送尖峰） */
    void updateOwnerVelocity(Player owner) {
        Vec3 pos = owner.position();
        Vec3 vel = this.lastOwnerPos == null ? Vec3.ZERO : pos.subtract(this.lastOwnerPos);
        this.lastOwnerPos = pos;
        if (vel.lengthSqr() > OWNER_SPEED_CLAMP * OWNER_SPEED_CLAMP) {
            vel = vel.normalize().scale(OWNER_SPEED_CLAMP);
        }
        this.ownerVelocity = vel;
        this.verticalFeedY += (Mth.clamp(vel.y, -3.0D, 3.0D) - this.verticalFeedY) * 0.5D;
    }

    /** 玩家实测速度（格/tick，已钳制） */
    double ownerSpeed() {
        return this.ownerVelocity.length();
    }

    /**
     * 无人机跟随速度：基础速度 + 玩家实测速度联动。
     * 玩家高速移动（坠落、鞘翅/创造/模组飞行等）时允许突破常规上限——
     * 保证速度 ≥ 玩家实测速度的 1.2 倍（+余量），实现实时紧跟而不依赖传送兜底；
     * 常规低速移动行为不变。
     */
    private double speedForOwner() {
        double playerSpeed = this.ownerVelocity.length();
        double speed = FLY_SPEED + playerSpeed * Config.DRONE_SPEED_FACTOR.get();
        double dynamicMax = Math.max(Config.DRONE_MAX_SPEED.get(), playerSpeed * 1.2D + 0.2D);
        return Mth.clamp(speed, FLY_SPEED, dynamicMax);
    }

    /** 碰撞检测：目标位置是否可通行（判定盒收窄，避免贴墙接触被误判为碰撞） */
    private boolean isPassable(Vec3 step) {
        if (step.lengthSqr() < 1.0E-8D) {
            return false;
        }
        return isBoxPassable(drone.getBoundingBox().move(step).deflate(BOX_SHRINK));
    }

    /**
     * 判定盒内是否通行：按碰撞形状与判定盒的<b>真实相交</b>判定。
     *
     * <p>不能用"形状非空即阻挡"：开着的门仍带贴边薄板形状（DoorBlock 未覆写
     * getCollisionShape，开态返回 3/16 薄板），会被整格误判为阻挡，导致无人机
     * 打开门后反而卡在门口。门类（含铁门）本就由无人机自动打开，视为可通行。</p>
     */
    private boolean isBoxPassable(AABB box) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = drone.level().getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(drone.level(), pos);
            if (shape.isEmpty()) {
                continue;
            }
            if (!shape.bounds().move(pos.getX(), pos.getY(), pos.getZ()).intersects(box)) {
                continue;
            }
            if (DronePathfinder.openedState(state) != null) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 执行位移（调用前需通过 isPassable 检测） */
    private void doMove(Vec3 step) {
        drone.setDeltaMovement(step);
        drone.move(MoverType.SELF, step);
    }

    /**
     * 绕障寻路：视线受阻时沿障碍固定一侧绕行。
     * 每帧重新比较左右两侧会导致方向反复切换（原地打转），故首次受阻时选定一侧并保持。
     */
    private boolean tryDetour(Vec3 target, double speed) {
        Vec3 delta = target.subtract(drone.position());
        if (delta.lengthSqr() < 1.0E-8D) {
            return false;
        }
        Vec3 forward = delta.normalize();
        // 步长自适应：慢速维持 1 格精度，高速按速度 90% 跟进（绕障时也能追上坠落/飞行）
        double step = Math.min(speed, Math.max(DETOUR_STEP_MAX, speed * 0.9D));

        if (this.wallFollowSide == 0) {
            this.wallFollowSide = chooseDetourSide(forward, target, step);
        }
        Steer best = bestSteer(forward, target, step, this.wallFollowSide);
        if (best == null) {
            // 该侧走不通：换另一侧
            this.wallFollowSide = -this.wallFollowSide;
            best = bestSteer(forward, target, step, this.wallFollowSide);
        }
        if (best == null) {
            drone.setDeltaMovement(Vec3.ZERO);
            return false;
        }
        doMove(best.step);
        rememberDetour(best.angle);
        return true;
    }

    /** 选择绕行侧：比较左右两侧最优候选的"位移后离目标距离"，取更近的一侧 */
    private int chooseDetourSide(Vec3 forward, Vec3 target, double step) {
        Steer left = bestSteer(forward, target, step, 1);
        Steer right = bestSteer(forward, target, step, -1);
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.score <= right.score ? 1 : -1;
    }

    /**
     * 在指定一侧挑选"位移后离目标最近"的可行方向。
     * 候选包含：纯垂直升降（目标在正上/正下方时水平基向量退化，只有它可行）、
     * 该侧的角度 × 水平/向上/向下。垂直与左右在同一评分下比较，因此上下寻路与左右寻路同等优先。
     */
    private Steer bestSteer(Vec3 forward, Vec3 target, double step, int side) {
        Steer best = better(null, new Vec3(0.0D, step, 0.0D), 0.0D, target);
        best = better(best, new Vec3(0.0D, -step, 0.0D), 0.0D, target);

        Vec3 flat = new Vec3(forward.x, 0.0D, forward.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            return best;
        }
        flat = flat.normalize();
        for (double angle : DETOUR_ANGLES) {
            if (side > 0 ? angle < 0.0D : angle > 0.0D) {
                continue;
            }
            for (int vertical : new int[]{0, 1, -1}) {
                Vec3 rotated = rotateY(flat, angle).scale(step);
                best = better(best, new Vec3(rotated.x, vertical * step, rotated.z), angle, target);
            }
        }
        return best;
    }

    /** 候选可通行且位移后离目标更近则替换最优；沿用上次绕行方向给评分加成，避免抖动 */
    private Steer better(Steer best, Vec3 candidate, double angle, Vec3 target) {
        if (!isPassable(candidate)) {
            return best;
        }
        double score = drone.position().add(candidate).distanceTo(target);
        if (this.detourTicks > 0 && Math.abs(angle - this.detourAngle) < 1.0D) {
            score -= DETOUR_MEMORY_BONUS;
        }
        if (best == null || score < best.score) {
            return new Steer(angle, candidate, score);
        }
        return best;
    }

    /** 记录绕行方向并刷新记忆时长 */
    private void rememberDetour(double angle) {
        this.detourAngle = angle;
        this.detourTicks = DETOUR_MEMORY_TICKS;
    }

    /** 绕 Y 轴旋转水平方向（角度制；运镜控制器复用同一约定） */
    static Vec3 rotateY(Vec3 vec, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3(vec.x * cos - vec.z * sin, vec.y, vec.x * sin + vec.z * cos);
    }

    /** 候选绕行方向（偏角 + 位移向量 + 评分） */
    private record Steer(double angle, Vec3 step, double score) {
    }
}
