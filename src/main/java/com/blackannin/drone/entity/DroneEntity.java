package com.blackannin.drone.entity;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.Config;
import com.blackannin.drone.registry.ModAttachments;
import com.blackannin.drone.registry.ModEntities;
import com.blackannin.drone.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 航拍无人机：悬浮跟随玩家，可手动指路，并可由玩家背包收回。
 * 飞行导航（目标平滑、全局 A* 寻路、局部绕障、卡住脱困）由 {@link DroneNavigator} 负责，
 * 本类只处理实体状态、同步、归属与交互。
 */
public class DroneEntity extends Mob implements OwnableEntity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER_UUID = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> MANUAL_CONTROL = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> CINEMATIC = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);

    /** 玩家爬行时的跟随距离：贴近玩家，便于从同一洞口穿过 */
    private static final double CRAWL_FOLLOW_DISTANCE = 1.2D;
    /** 仅服务端：遥控器连续输入（由网络包每 tick 刷新） */
    private float remoteForward;
    private float remoteStrafe;
    private float remoteUp;
    private float remoteYawDelta;
    private float remotePitchDelta;
    /** 仅服务端：被无人机强行打开的门（原状态），离开后恢复 */
    private final Map<BlockPos, BlockState> forcedOpenDoors = new HashMap<>();
    /** 仅服务端：起飞倒计时。客户端从不读取，故用普通字段而非同步数据，避免起飞期间每 tick 发同步包 */
    private int takeoffTicks;
    /** 仅服务端：飞行导航 */
    final DroneNavigator navigator = new DroneNavigator(this);
    /** 仅服务端：自动运镜（电影模式）控制器 */
    private final DroneCinematographer cinematographer = new DroneCinematographer();

    public DroneEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
        this.setNoGravity(true);
        this.setNoAi(true);
        this.noPhysics = false;
        // 无敌：仅 /kill 等可穿透无敌的伤害能杀死它；回收只能由玩家主动操作
        this.setInvulnerable(true);
    }

    /** 免疫火焰（不燃烧、无灼烧动画与伤害） */
    @Override
    public boolean fireImmune() {
        return true;
    }

    /** 除 /kill 一类可穿透无敌的伤害外全部免疫（燃烧、窒息、摔落、怪物攻击等） */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /** 不被视作可攻击目标：怪物不会主动攻击它 */
    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER_UUID, Optional.empty());
        builder.define(MANUAL_CONTROL, false);
        builder.define(CINEMATIC, false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FLYING_SPEED, 0.6D)
                .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    @Override
    public void tick() {
        super.tick();
        this.setNoGravity(true);
        this.fallDistance = 0;

        if (this.level().isClientSide) {
            return;
        }

        Player owner = getOwner();
        if (owner == null && this.level() instanceof ServerLevel serverLevel) {
            // owner 为空有两种情况：玩家真的离线，或玩家正在跨维度传送
            // （旧维度里 getPlayerByUUID 已找不到玩家，但玩家仍在线）。
            // 跨维度时不能销毁——传送玩家所在维度后由跨维度跟随逻辑接管。
            UUID ownerId = this.getOwnerUUID();
            Player online = ownerId == null ? null
                    : serverLevel.getServer().getPlayerList().getPlayer(ownerId);
            if (online == null) {
                // 玩家离线/退出游戏：不再掉落销毁，原地悬停并随存档持久化。
                // 玩家回来后由本 tick 逻辑自动恢复跟随，推流随相机渲染器自动继续
                return;
            }
            if (online.level() instanceof ServerLevel target
                    && !target.dimension().equals(this.level().dimension())) {
                // 玩家已进入另一个维度：跟随过去
                com.blackannin.drone.BlackAnninsDrone.LOGGER.info("[跨维度] tick轮询传送: {} -> {}",
                        this.level().dimension().location(), target.dimension().location());
                // 跨维度会重建实体并丢弃本实例：先还原旧维度中被强开的门，防止门永久停留在开态
                restoreForcedDoors();
                this.teleportTo(target, online.getX(), online.getY() + Config.DRONE_FOLLOW_HEIGHT.get(),
                        online.getZ(), Set.of(), this.getYRot(), this.getXRot());
            }
            // 同维度但 owner 暂不可得（极短窗口）：原地悬停等待下一 tick
            return;
        }
        if (!owner.isAlive()) {
            // 玩家死亡：原地悬停等待复活，复活后由下方逻辑自动跟过去
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        // 孤儿检测：玩家的"当前无人机"附件指向别的实体时，自己是跨维度时序遗留的孤儿，
        // 主动掉落为物品，避免与当前无人机同时存在（旧维度遗留 + 新放置的组合场景）
        java.util.Optional<UUID> active = owner.getData(ModAttachments.ACTIVE_DRONE.get());
        if (active.isPresent() && !active.get().equals(this.getUUID())) {
            dropAsItem();
            this.discard();
            return;
        }
        if (!owner.level().dimension().equals(this.level().dimension()) && owner.level() instanceof ServerLevel target) {
            // 玩家跨维度传送：无人机跟随到对应维度（同理先还原旧维度的强开门）
            restoreForcedDoors();
            this.teleportTo(target, owner.getX(), owner.getY() + Config.DRONE_FOLLOW_HEIGHT.get(), owner.getZ(),
                    Set.of(), this.getYRot(), this.getXRot());
            return;
        }

        if (this.takeoffTicks < Config.DRONE_TAKEOFF_DELAY.get()) {
            this.takeoffTicks++;
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }

        boolean cinematic = this.entityData.get(CINEMATIC);
        // 距离过远：强制传送回玩家身边（仅自动跟随模式；手动/运镜模式各有自己的活动范围，不受该设置影响）。
        // 高速移动（鞘翅等）时按实测速度放宽阈值：跟随速度已 ≥ 玩家 1.2 倍，
        // 传送只应作为真正掉队的兜底，而非高速跟随的常规手段（否则画面一顿一顿）
        double teleportDistance = Math.max(Config.DRONE_TELEPORT_DISTANCE.get(),
                this.navigator.ownerSpeed() * 10.0D);
        if (!this.entityData.get(MANUAL_CONTROL) && !cinematic
                && this.position().distanceToSqr(owner.position()) > teleportDistance * teleportDistance) {
            teleportToOwner(owner);
        }

        // 先开门再移动：移动当帧门就已打开，避免无人机先撞一次门板
        openDoorsAlongPath();

        if (this.entityData.get(MANUAL_CONTROL)) {
            // 手动模式视角完全由玩家的鼠标/摇杆控制
            this.navigator.flyManual(owner, remoteForward, remoteStrafe, remoteUp, remoteYawDelta, remotePitchDelta);
            this.remoteYawDelta = 0.0F;
            this.remotePitchDelta = 0.0F;
        } else if (cinematic) {
            // 自动运镜：机位实时跟随玩家；返回 false 表示视线长时间受阻，自动退出并恢复跟随
            if (!this.cinematographer.tick(this, owner)) {
                this.entityData.set(CINEMATIC, false);
                this.setDeltaMovement(Vec3.ZERO);
                owner.displayClientMessage(Component.translatable("chat.blackannin_drone.cinema_ended"), true);
            }
        } else {
            // 实测玩家速度（相邻 tick 位移差）：服务端 getDeltaMovement 对玩家不可靠
            this.navigator.updateOwnerVelocity(owner);
            this.navigator.tick();
            boolean ownerOnGround = owner.onGround();
            if (ownerOnGround) {
                this.navigator.updateGroundAnchor(owner);
            }
            // 落地时跟随高度（跑酷逐格升降立即生效）；空中只有"非跳跃弧线"（飞行、坠落）才跟随，
            // 因此跑跳与原地连跳都不会带动无人机上下移动
            boolean followHeight = ownerOnGround || !this.navigator.isJumpArc(owner, ownerOnGround);
            // 玩家爬行（钻活板门/1 格高通道）时，跟随点改为贴身且与玩家同高，
            // 否则常规跟随点会落在方块里，无人机就会在外面绕飞而不是从洞口跟上
            Vec3 raw = isOwnerCrawling(owner) ? crawlFollowPoint(owner) : followPoint(owner);
            Vec3 routed = this.navigator.routeAlongPath(raw, owner);
            Vec3 target = this.navigator.smoothed(routed, followHeight);
            this.navigator.flyToward(target, owner, followHeight);
            // 跟随时镜头始终对准玩家（OBS 画面优先）：偏航与俯仰双轴平滑追踪，
            // 无人机位置滞后（坠落/爬升追击中）时玩家也始终在画面内
            aimCameraAtOwner(owner);
        }
    }

    /**
     * 自动打开挡路的所有门类（含铁门：无人机作为电子设备直接接入控制打开），
     * 使无人机能穿过关闭的门继续跟随；离开后恢复原状态。
     */
    private void openDoorsAlongPath() {
        AABB area = this.getBoundingBox().inflate(0.9D);
        BlockPos min = BlockPos.containing(area.minX, area.minY, area.minZ);
        BlockPos max = BlockPos.containing(area.maxX, area.maxY, area.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = this.level().getBlockState(pos);
            BlockState opened = DronePathfinder.openedState(state);
            if (opened == null) {
                continue;
            }
            if (forcedOpenDoors.putIfAbsent(pos.immutable(), state) == null) {
                BlackAnninsDrone.LOGGER.debug("无人机强开门: {} ({})", pos, state.getBlock());
            }
            this.level().setBlock(pos, opened, 3);
            if (state.getBlock() instanceof DoorBlock door) {
                BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
                BlockState otherState = this.level().getBlockState(other);
                BlockState otherOpened = DronePathfinder.openedState(otherState);
                if (otherOpened != null) {
                    forcedOpenDoors.putIfAbsent(other.immutable(), otherState);
                    this.level().setBlock(other, otherOpened, 3);
                }
            }
        }
        // 离开门后恢复原状态
        AABB clearArea = this.getBoundingBox().inflate(1.2D);
        forcedOpenDoors.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            if (!clearArea.intersects(new AABB(pos))) {
                this.level().setBlock(pos, entry.getValue(), 3);
                return true;
            }
            return false;
        });
    }

    /** 遥控器输入（服务端）：缓存到下一次 tick 应用；任何非零输入即接管为手动模式 */
    public void receiveRemoteInput(float forward, float strafe, float up, float yawDelta, float pitchDelta) {
        if (forward != 0.0F || strafe != 0.0F || up != 0.0F || yawDelta != 0.0F || pitchDelta != 0.0F) {
            this.entityData.set(MANUAL_CONTROL, true);
        }
        this.remoteForward = Mth.clamp(forward, -1.0F, 1.0F);
        this.remoteStrafe = Mth.clamp(strafe, -1.0F, 1.0F);
        this.remoteUp = Mth.clamp(up, -1.0F, 1.0F);
        this.remoteYawDelta += yawDelta;
        this.remotePitchDelta += pitchDelta;
    }

    /**
     * 跟随模式镜头始终对准玩家（OBS 画面优先）：偏航与俯仰双轴指数平滑追踪。
     * 无人机物理位置允许滞后（坠落/爬升追击中），但玩家始终保持在画面内；
     * 玩家接近正上/正下方时保持当前偏航，避免方位角在零分量上抖动。
     * 唯一旋转控制器——不再有"朝向玩家面向"与"对准玩家"两套逻辑互相拉扯。
     */
    private void aimCameraAtOwner(Player owner) {
        Vec3 delta = owner.getEyePosition().subtract(this.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 1.0E-3D) {
            return;
        }
        float yawToOwner = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitchToOwner = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        float yawDelta = Mth.degreesDifference(this.getYRot(), yawToOwner);
        this.setYRot(this.getYRot() + yawDelta * 0.25F);
        this.setXRot(this.getXRot() + (pitchToOwner - this.getXRot()) * 0.25F);
        this.setYBodyRot(this.getYRot());
        this.setYHeadRot(this.getYRot());
    }

    /** 玩家是否处于爬行/低矮姿态（钻活板门、1 格高通道、游泳等） */
    private boolean isOwnerCrawling(Player owner) {
        return owner.getPose() == Pose.SWIMMING || owner.getBbHeight() < 1.0F;
    }

    /** 爬行时的跟随点：贴着玩家、与玩家同高，使无人机从同一洞口跟过去而不绕飞 */
    private Vec3 crawlFollowPoint(Player owner) {
        Vec3 offset = this.position().subtract(owner.position());
        Vec3 flat = new Vec3(offset.x, 0.0D, offset.z);
        Vec3 behind = flat.lengthSqr() < 1.0E-4D
                ? Vec3.ZERO
                : flat.normalize().scale(CRAWL_FOLLOW_DISTANCE);
        return owner.position().add(behind).add(0.0D, 0.2D, 0.0D);
    }

    /** 常规跟随点：玩家身后一定距离、上方一定高度 */
    private Vec3 followPoint(Player owner) {
        Vec3 look = owner.getViewVector(1.0F);
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        Vec3 back;
        if (horizontal < 0.01) {
            back = Vec3.directionFromRotation(0, owner.getYRot()).scale(-1.0);
        } else {
            back = new Vec3(-look.x, 0, -look.z).normalize();
        }
        return owner.position().add(back.scale(Config.DRONE_FOLLOW_DISTANCE.get()))
                .add(0, Config.DRONE_FOLLOW_HEIGHT.get(), 0);
    }

    /** 传送到玩家跟随点，用于距离过远或长时间被阻挡 */
    void teleportToOwner(Player owner) {
        Vec3 point = followPoint(owner);
        this.navigator.reset();
        this.teleportTo(point.x, point.y, point.z);
        this.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * 无人机不使用传送门：vanilla 的传送门传送会与跨维度跟随逻辑互相干扰——
     * 无人机跟随中穿过传送门时被 vanilla 传走，跟随逻辑又把它拉回玩家维度，
     * 循环产生多个实体且旧的被移除（表现为无人机消失/多机跟随）。
     * 玩家的跨维度跟随由 tick 中的 owner==null 分支统一处理。
     */
    @Override
    protected void handlePortal() {
    }

    /**
     * 实体移除（收回、离线掉落、孤儿回收、/kill）时还原全部强开的门，防止门永久停留在开态。
     * 跨维度移除（CHANGED_DIMENSION）不在此处理：届时 level() 可能已指向新维度，
     * 用旧维度坐标写方块会误改新维度，因此跨维度改为在 teleportTo 调用前显式还原。
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (!this.level().isClientSide
                && (reason == Entity.RemovalReason.DISCARDED || reason == Entity.RemovalReason.KILLED)) {
            restoreForcedDoors();
        }
        super.remove(reason);
    }

    /** 还原所有被强开的门（还原后清空映射，可幂等重复调用） */
    private void restoreForcedDoors() {
        if (this.forcedOpenDoors.isEmpty()) {
            return;
        }
        this.forcedOpenDoors.forEach((pos, state) -> this.level().setBlock(pos, state, 3));
        this.forcedOpenDoors.clear();
    }

    /** 重置视角：对准玩家朝向、俯仰归零（操控台「重置视角」与「恢复跟随」时调用） */
    public void resetView(Player owner) {
        float yaw = owner.getYRot();
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.setYBodyRot(yaw);
        this.setXRot(0.0F);
        // 同步旧值，避免渲染插值把"瞬间重置"表现成快速旋转
        this.yRotO = yaw;
        this.xRotO = 0.0F;
    }

    /** 恢复自动跟随（同时退出运镜：跟随与运镜/手动互斥） */
    public void stopManualControl() {
        this.entityData.set(MANUAL_CONTROL, false);
        this.entityData.set(CINEMATIC, false);
    }

    /** 进入手动模式（操控台"手动操纵"按钮/按键触发）；手动与运镜互斥，接管即退出运镜 */
    public void enterManualControl() {
        this.entityData.set(MANUAL_CONTROL, true);
        this.entityData.set(CINEMATIC, false);
    }

    /** 切换自动运镜（操控台按钮触发）：运镜与手动模式互斥 */
    public void toggleCinematic(Player owner) {
        if (this.entityData.get(CINEMATIC)) {
            this.entityData.set(CINEMATIC, false);
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        this.entityData.set(MANUAL_CONTROL, false);
        this.cinematographer.start(this, owner);
        this.entityData.set(CINEMATIC, true);
    }

    /** 是否处于自动运镜（客户端同步位，供操控台按钮状态与遥测显示） */
    public boolean isCinematic() {
        return this.entityData.get(CINEMATIC);
    }

    /** 是否存在被强开的门：导航需优先沿 A\* 路点对准门洞，避免视线直飞撞上门框 */
    public boolean hasForcedOpenDoors() {
        return !this.forcedOpenDoors.isEmpty();
    }

    /** 是否处于手动操控（客户端同步位，供操控台显示模式） */
    public boolean isManuallyControlled() {
        return this.entityData.get(MANUAL_CONTROL);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && tryRetrieve(player)) {
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    /** 收回无人机：校验归属后返还物品并移除实体 */
    public boolean tryRetrieve(Player player) {
        if (this.level().isClientSide) {
            return player.getUUID().equals(getOwnerUUID());
        }
        if (!player.getUUID().equals(getOwnerUUID())) {
            return false;
        }
        if (!player.getAbilities().instabuild) {
            ItemStack stack = new ItemStack(ModItems.AERIAL_DRONE.get());
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        player.setData(ModAttachments.ACTIVE_DRONE.get(), Optional.empty());
        player.displayClientMessage(Component.translatable("chat.blackannin_drone.retrieved"), true);
        this.discard();
        return true;
    }

    /** 掉落为物品（仅孤儿回收时；玩家离线不再掉落，原地留驻） */
    private void dropAsItem() {
        this.spawnAtLocation(new ItemStack(ModItems.AERIAL_DRONE.get()));
        Player owner = getOwner();
        if (owner != null) {
            owner.setData(ModAttachments.ACTIVE_DRONE.get(), Optional.empty());
        }
    }

    public void setOwner(Player player) {
        this.entityData.set(OWNER_UUID, Optional.of(player.getUUID()));
        player.setData(ModAttachments.ACTIVE_DRONE.get(), Optional.of(this.getUUID()));
    }

    @Nullable
    @Override
    public UUID getOwnerUUID() {
        return this.entityData.get(OWNER_UUID).orElse(null);
    }

    @Nullable
    @Override
    public Player getOwner() {
        UUID uuid = getOwnerUUID();
        return uuid == null ? null : this.level().getPlayerByUUID(uuid);
    }

    /** 查找玩家的无人机：优先用附件记录的 UUID，退化为范围内搜索归属者的无人机 */
    @Nullable
    public static DroneEntity findOwned(Player player) {
        Optional<UUID> stored = player.getData(ModAttachments.ACTIVE_DRONE.get());
        if (stored.isPresent() && player.level() instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(stored.get());
            if (entity instanceof DroneEntity drone && drone.isAlive()) {
                return drone;
            }
        }
        AABB area = player.getBoundingBox().inflate(128);
        List<DroneEntity> drones = player.level().getEntitiesOfClass(DroneEntity.class, area,
                d -> player.getUUID().equals(d.getOwnerUUID()));
        return drones.isEmpty() ? null : drones.get(0);
    }

    /** /dronespawn 命令生成：玩家前方 3 格悬空认主（管理员/快速测试用） */
    @Nullable
    public static DroneEntity spawnFor(ServerPlayer player) {
        if (findOwned(player) != null) {
            return null;
        }
        Vec3 look = player.getViewVector(1.0F);
        Vec3 pos = player.position().add(look.x * 3.0D, 0.8D, look.z * 3.0D);
        DroneEntity drone = ModEntities.AERIAL_DRONE.get().spawn((ServerLevel) player.level(),
                ItemStack.EMPTY, player, BlockPos.containing(pos.x, pos.y, pos.z),
                net.minecraft.world.entity.MobSpawnType.COMMAND, true, true);
        if (drone != null) {
            drone.setOwner(player);
            drone.moveTo(pos.x, pos.y, pos.z, player.getYRot(), 0.0F);
        }
        return drone;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        if (getOwnerUUID() != null) {
            compound.putUUID("Owner", getOwnerUUID());
        }
        compound.putInt("TakeoffTicks", this.takeoffTicks);
        compound.putBoolean("ManualControl", this.entityData.get(MANUAL_CONTROL));
        compound.putBoolean("Cinematic", this.entityData.get(CINEMATIC));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.hasUUID("Owner")) {
            this.entityData.set(OWNER_UUID, Optional.of(compound.getUUID("Owner")));
        }
        this.takeoffTicks = compound.getInt("TakeoffTicks");
        this.entityData.set(MANUAL_CONTROL, compound.getBoolean("ManualControl"));
        this.entityData.set(CINEMATIC, compound.getBoolean("Cinematic"));
    }
}
