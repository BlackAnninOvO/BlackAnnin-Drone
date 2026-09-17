package com.blackannin.drone.entity;

import com.blackannin.drone.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
    /** 速度平滑系数：让移动加减速更柔和 */
    private static final double VELOCITY_SMOOTHING = 0.35D;

    /** 绕障方向记忆时长（tick）：记住当前绕行方向，避免在障碍边缘来回抖动 */
    private static final int DETOUR_MEMORY_TICKS = 20;
    /** 绕障候选方向的角度（度）：15° 步长，保证能对准 1 格宽的门洞/缺口 */
    private static final double[] DETOUR_ANGLES = {
            0.0D, 15.0D, -15.0D, 30.0D, -30.0D, 45.0D, -45.0D, 60.0D, -60.0D,
            75.0D, -75.0D, 90.0D, -90.0D, 105.0D, -105.0D, 120.0D, -120.0D,
            135.0D, -135.0D, 150.0D, -150.0D, 165.0D, -165.0D, 180.0D};
    /** 绕障单步最大位移：窄缝/门洞中不至于一次跨过头 */
    private static final double DETOUR_STEP_MAX = 1.0D;
    /** 沿用上次绕行方向的评分加成，避免在障碍边缘来回抖动 */
    private static final double DETOUR_MEMORY_BONUS = 0.75D;

    /** 重新搜索全局路径的间隔（tick） */
    private static final int REPATH_INTERVAL_TICKS = 10;
    /** 距目标超过该距离（格）才进行全局寻路，近距离由局部转向处理 */
    private static final double REPATH_MIN_DISTANCE = 6.0D;
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
        double newY = followHeight ? current.y + dy * VERTICAL_SMOOTHING : current.y;
        this.smoothedTarget = new Vec3(
                current.x + (raw.x - current.x) * HORIZONTAL_SMOOTHING,
                newY,
                current.z + (raw.z - current.z) * HORIZONTAL_SMOOTHING);
        return this.smoothedTarget;
    }

    /** 平地原地连续跳跃（水平几乎不动且高度在跳跃范围内）：忽略其上下浮动 */
    boolean isInPlaceJump(Player owner, boolean ownerOnGround) {
        if (ownerOnGround || this.groundAnchor == null) {
            return false;
        }
        Vec3 pos = owner.position();
        double dx = pos.x - this.groundAnchor.x;
        double dz = pos.z - this.groundAnchor.z;
        return dx * dx + dz * dz < 0.25D
                && Math.abs(pos.y - this.groundAnchor.y) < 1.5D;
    }

    /**
     * 全局路径跟随：距离较远或看不到目标时用 A* 搜索一条可通行路径，并按路点前进。
     * 局部转向（视线/绕障）无法定位"封闭房间的小洞口"这类入口，必须靠全局搜索。
     */
    Vec3 routeAlongPath(Vec3 goal, Player owner) {
        boolean blocked = !hasLineOfSight(goal);
        if (!blocked && drone.position().distanceToSqr(goal) < REPATH_MIN_DISTANCE * REPATH_MIN_DISTANCE) {
            this.path = List.of();
            return goal;
        }
        // 寻路终点取玩家自身位置：跟随点可能落在墙体/天花板内（不可通行），会导致搜索失败
        Vec3 searchGoal = owner.position();
        boolean needRepath = this.path.isEmpty()
                || this.pathIndex >= this.path.size()
                || --this.repathTicks <= 0
                || this.pathGoal == null
                || this.pathGoal.distanceToSqr(searchGoal) > REPATH_GOAL_MOVE_SQR;
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

    /** 朝目标飞行：视线通畅直飞，受阻则绕行，无路可走则传送 */
    void flyToward(Vec3 target, Player owner) {
        drone.getNavigation().stop();

        if (hasLineOfSight(target) && flyStraight(target, owner)) {
            this.wallFollowSide = 0;
            this.detourTicks = 0;
            return;
        }
        double speed = speedForOwner(owner);
        if (tryDetour(target, speed)) {
            checkProgress(target, owner);
            return;
        }
        drone.teleportToOwner(owner);
    }

    /** 朝目标直飞：带速度平滑；返回是否成功移动一步 */
    private boolean flyStraight(Vec3 target, Player owner) {
        Vec3 delta = target.subtract(drone.position());
        double dist = delta.length();
        if (dist < 0.05D) {
            drone.setDeltaMovement(Vec3.ZERO);
            return true;
        }
        double speed = speedForOwner(owner);
        Vec3 desired = delta.scale(Math.min(1.0D, speed / dist));
        this.smoothedVelocity = this.smoothedVelocity.add(desired.subtract(this.smoothedVelocity).scale(VELOCITY_SMOOTHING));
        if (this.smoothedVelocity.lengthSqr() > speed * speed) {
            this.smoothedVelocity = this.smoothedVelocity.normalize().scale(speed);
        }
        Vec3 step = this.smoothedVelocity;
        if (!isPassable(step)) {
            this.smoothedVelocity = Vec3.ZERO;
            return false;
        }
        doMove(step);
        return true;
    }

    /** 与目标之间是否有通畅的视线（只判实心方块，放行水、打开的门/活板门等） */
    private boolean hasLineOfSight(Vec3 target) {
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
        if (targetDistance < STUCK_MIN_TARGET_DISTANCE || netDisplacement >= STUCK_NET_DISPLACEMENT) {
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

    /** 无人机速度随玩家移动速度提升，并受最大速度限制 */
    private double speedForOwner(Player owner) {
        Vec3 velocity = owner.getDeltaMovement();
        double playerSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double speed = FLY_SPEED + playerSpeed * Config.DRONE_SPEED_FACTOR.get();
        return Mth.clamp(speed, FLY_SPEED, Config.DRONE_MAX_SPEED.get());
    }

    /** 碰撞检测：目标位置是否可通行（判定盒收窄 0.05 格，避免贴墙接触被误判为碰撞） */
    private boolean isPassable(Vec3 step) {
        if (step.lengthSqr() < 1.0E-8D) {
            return false;
        }
        return drone.level().noCollision(drone, drone.getBoundingBox().move(step).deflate(0.05D));
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
        double step = Math.min(speed, DETOUR_STEP_MAX);

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

    /** 绕 Y 轴旋转水平方向（角度制） */
    private static Vec3 rotateY(Vec3 vec, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3(vec.x * cos - vec.z * sin, vec.y, vec.x * sin + vec.z * cos);
    }

    /** 候选绕行方向（偏角 + 位移向量 + 评分） */
    private record Steer(double angle, Vec3 step, double score) {
    }
}
