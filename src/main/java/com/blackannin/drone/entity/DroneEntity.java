package com.blackannin.drone.entity;

import com.blackannin.drone.Config;
import com.blackannin.drone.registry.ModAttachments;
import com.blackannin.drone.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
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

    /** 手动指路每次移动的距离（格） */
    private static final double MOVE_STEP = 1.0D;
    /** 玩家爬行时的跟随距离：贴近玩家，便于从同一洞口穿过 */
    private static final double CRAWL_FOLLOW_DISTANCE = 1.2D;
    /** 手动模式下转向所需的最小水平速度：低于该值视为悬停，保持当前朝向 */
    private static final double YAW_TURN_MIN_SPEED = 0.12D;

    /** 仅服务端：手动悬停点 */
    private Vec3 hoverTarget = Vec3.ZERO;
    /** 仅服务端：飞行导航 */
    private final DroneNavigator navigator = new DroneNavigator(this);

    public DroneEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
        this.setNoGravity(true);
        this.setNoAi(true);
        this.noPhysics = false;
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

        // 距离过远：强制传送回玩家身边，避免无人机被落下或卡在远处
        double teleportDistance = Config.DRONE_TELEPORT_DISTANCE.get();
        if (this.position().distanceToSqr(owner.position()) > teleportDistance * teleportDistance) {
            teleportToOwner(owner);
        }

        this.navigator.tick();
        boolean ownerOnGround = owner.onGround();
        if (ownerOnGround) {
            this.navigator.updateGroundAnchor(owner);
        }
        // 正常跳跃/坠落/飞行都跟随高度；只有平地原地连续跳跃忽略上下浮动
        boolean followHeight = ownerOnGround || !this.navigator.isInPlaceJump(owner, ownerOnGround);

        if (this.entityData.get(MANUAL_CONTROL)) {
            Vec3 target = this.navigator.smoothed(clampToOwner(owner, this.hoverTarget), followHeight);
            this.navigator.flyToward(target, owner);
            faceMovementDirection();
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

    /** 平滑朝向目标偏航角（镜头朝向） */
    private void setYawSmooth(float targetYaw) {
        float delta = Mth.degreesDifference(this.getYRot(), targetYaw);
        this.setYRot(this.getYRot() + delta * 0.2F);
        this.setYBodyRot(this.getYRot());
        this.setYHeadRot(this.getYRot());
    }

    /**
     * 手动模式：镜头朝无人机的实际移动方向。
     * 用实际速度而非"到目标点的方向"——悬停在目标附近时残余位置抖动会让后者来回翻转，
     * 表现为视角乱转；速度低于阈值时保持当前朝向。
     */
    private void faceMovementDirection() {
        Vec3 velocity = this.getDeltaMovement();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal > YAW_TURN_MIN_SPEED) {
            setYawSmooth((float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z)));
        }
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

    /** 手动指路点限制在玩家周围最大半径内 */
    private Vec3 clampToOwner(Player owner, Vec3 target) {
        double max = Config.DRONE_MAX_RADIUS.get();
        Vec3 ownerPos = owner.position();
        Vec3 offset = target.subtract(ownerPos);
        Vec3 horizontal = new Vec3(offset.x, 0, offset.z);
        double dist = horizontal.length();
        if (dist > max && dist > 1.0E-4) {
            horizontal = horizontal.scale(max / dist);
        }
        double y = Mth.clamp(offset.y, 0.2D, Math.max(Config.DRONE_FOLLOW_HEIGHT.get() + 4.0, max));
        return ownerPos.add(horizontal.x, y, horizontal.z);
    }

    /** 传送到玩家跟随点，用于距离过远或长时间被阻挡 */
    void teleportToOwner(Player owner) {
        Vec3 point = this.entityData.get(MANUAL_CONTROL)
                ? clampToOwner(owner, this.hoverTarget)
                : followPoint(owner);
        this.hoverTarget = point;
        this.navigator.reset();
        this.teleportTo(point.x, point.y, point.z);
        this.setDeltaMovement(Vec3.ZERO);
    }

    /** 控制面板/按键指路：按玩家朝向在指定方向移动一次 */
    public void nudgeFromOwner(Player owner, byte action) {
        Vec3 look = Vec3.directionFromRotation(0, owner.getYRot());
        Vec3 right = new Vec3(look.z, 0, -look.x);
        Vec3 base = this.entityData.get(MANUAL_CONTROL) ? this.hoverTarget : this.position();
        Vec3 next = switch (action) {
            case 1 -> base.add(look.scale(MOVE_STEP));
            case 2 -> base.subtract(look.scale(MOVE_STEP));
            case 3 -> base.subtract(right.scale(MOVE_STEP));
            case 4 -> base.add(right.scale(MOVE_STEP));
            case 5 -> base.add(0, MOVE_STEP, 0);
            case 6 -> base.add(0, -MOVE_STEP, 0);
            default -> base;
        };
        this.hoverTarget = clampToOwner(owner, next);
        this.entityData.set(MANUAL_CONTROL, true);
    }

    /** 恢复自动跟随 */
    public void stopManualControl() {
        this.entityData.set(MANUAL_CONTROL, false);
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

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        if (getOwnerUUID() != null) {
            compound.putUUID("Owner", getOwnerUUID());
        }
        compound.putInt("TakeoffTicks", this.entityData.get(TAKEOFF_TICKS));
        compound.putBoolean("ManualControl", this.entityData.get(MANUAL_CONTROL));
        compound.putDouble("HoverX", this.hoverTarget.x);
        compound.putDouble("HoverY", this.hoverTarget.y);
        compound.putDouble("HoverZ", this.hoverTarget.z);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.hasUUID("Owner")) {
            this.entityData.set(OWNER_UUID, Optional.of(compound.getUUID("Owner")));
        }
        this.entityData.set(TAKEOFF_TICKS, compound.getInt("TakeoffTicks"));
        this.entityData.set(MANUAL_CONTROL, compound.getBoolean("ManualControl"));
        this.hoverTarget = new Vec3(compound.getDouble("HoverX"), compound.getDouble("HoverY"), compound.getDouble("HoverZ"));
    }
}
