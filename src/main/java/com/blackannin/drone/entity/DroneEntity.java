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
    private static final EntityDataAccessor<Integer> TAKEOFF_TICKS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> MANUAL_CONTROL = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);

    /** 玩家爬行时的跟随距离：贴近玩家，便于从同一洞口穿过 */
    private static final double CRAWL_FOLLOW_DISTANCE = 1.2D;
    /** 手动模式下转向所需的最小水平速度：低于该值视为悬停，保持当前朝向 */
    /** 仅服务端：遥控器连续输入（由网络包每 tick 刷新） */
    private float remoteForward;
    private float remoteStrafe;
    private float remoteUp;
    private float remoteYawDelta;
    private float remotePitchDelta;
    /** 仅服务端：被无人机强行打开的门（原状态），离开后恢复 */
    private final Map<BlockPos, BlockState> forcedOpenDoors = new HashMap<>();
    /** 仅服务端：飞行导航 */
    private final DroneNavigator navigator = new DroneNavigator(this);

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
        builder.define(TAKEOFF_TICKS, 0);
        builder.define(MANUAL_CONTROL, false);
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
        if (owner == null) {
            // 玩家离线：掉落为物品并移除，避免留下无人认领的实体
            dropAsItem();
            this.discard();
            return;
        }
        if (!owner.isAlive()) {
            // 玩家死亡：原地悬停等待复活，复活后由下方逻辑自动跟过去
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (!owner.level().dimension().equals(this.level().dimension()) && owner.level() instanceof ServerLevel target) {
            // 玩家跨维度传送：无人机跟随到对应维度
            this.teleportTo(target, owner.getX(), owner.getY() + Config.DRONE_FOLLOW_HEIGHT.get(), owner.getZ(),
                    Set.of(), this.getYRot(), this.getXRot());
            return;
        }

        int ticks = this.entityData.get(TAKEOFF_TICKS);
        if (ticks < Config.DRONE_TAKEOFF_DELAY.get()) {
            this.entityData.set(TAKEOFF_TICKS, ticks + 1);
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }

        // 距离过远：强制传送回玩家身边（仅自动跟随模式；手动模式由操控半径软限制，不受该设置影响）
        if (!this.entityData.get(MANUAL_CONTROL)) {
            double teleportDistance = Config.DRONE_TELEPORT_DISTANCE.get();
            if (this.position().distanceToSqr(owner.position()) > teleportDistance * teleportDistance) {
                teleportToOwner(owner);
            }
        }

        // 先开门再移动：移动当帧门就已打开，避免无人机先撞一次门板
        openDoorsAlongPath();
        this.navigator.tick();
        boolean ownerOnGround = owner.onGround();
        if (ownerOnGround) {
            this.navigator.updateGroundAnchor(owner);
        }
        // 落地时跟随高度（跑酷逐格升降立即生效）；空中只有"非跳跃弧线"（飞行、坠落）才跟随，
        // 因此跑跳与原地连跳都不会带动无人机上下移动
        boolean followHeight = ownerOnGround || !this.navigator.isJumpArc(owner, ownerOnGround);

        if (this.entityData.get(MANUAL_CONTROL)) {
            // 手动模式视角完全由玩家的鼠标/摇杆控制
            this.navigator.flyManual(owner, remoteForward, remoteStrafe, remoteUp, remoteYawDelta, remotePitchDelta);
            this.remoteYawDelta = 0.0F;
            this.remotePitchDelta = 0.0F;
        } else {
            // 玩家爬行（钻活板门/1 格高通道）时，跟随点改为贴身且与玩家同高，
            // 否则常规跟随点会落在方块里，无人机就会在外面绕飞而不是从洞口跟上
            Vec3 raw = isOwnerCrawling(owner) ? crawlFollowPoint(owner) : followPoint(owner);
            Vec3 routed = this.navigator.routeAlongPath(raw, owner);
            Vec3 target = this.navigator.smoothed(routed, followHeight);
            this.navigator.flyToward(target, owner);
            // 跟随时镜头朝主人面向的方向，FPV 呈现前进视角
            setYawSmooth(owner.getYRot());
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
                BlackAnninsDrone.LOGGER.info("无人机强开门: {} ({})", pos, state.getBlock());
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

    /** 平滑朝向目标偏航角（镜头朝向） */
    private void setYawSmooth(float targetYaw) {
        float delta = Mth.degreesDifference(this.getYRot(), targetYaw);
        this.setYRot(this.getYRot() + delta * 0.2F);
        this.setYBodyRot(this.getYRot());
        this.setYHeadRot(this.getYRot());
    }

    /**
     * 手动模式视角完全由玩家的鼠标/摇杆控制（v0.5.0 依需求取消"朝移动方向转头"）。
     */

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

    /** 恢复自动跟随 */
    public void stopManualControl() {
        this.entityData.set(MANUAL_CONTROL, false);
    }

    /** 进入手动模式（操控台"手动操纵"按钮/按键触发，输入到达前先切换状态） */
    public void enterManualControl() {
        this.entityData.set(MANUAL_CONTROL, true);
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

    /** 掉落为物品（玩家离线时） */
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
        compound.putInt("TakeoffTicks", this.entityData.get(TAKEOFF_TICKS));
        compound.putBoolean("ManualControl", this.entityData.get(MANUAL_CONTROL));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.hasUUID("Owner")) {
            this.entityData.set(OWNER_UUID, Optional.of(compound.getUUID("Owner")));
        }
        this.entityData.set(TAKEOFF_TICKS, compound.getInt("TakeoffTicks"));
        this.entityData.set(MANUAL_CONTROL, compound.getBoolean("ManualControl"));
    }
}
