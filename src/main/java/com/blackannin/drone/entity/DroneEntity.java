package com.blackannin.drone.entity;

import com.blackannin.drone.Config;
import com.blackannin.drone.registry.ModAttachments;
import com.blackannin.drone.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DroneEntity extends Mob implements OwnableEntity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER_UUID = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> TAKEOFF_TICKS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> MANUAL_CONTROL = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);

    private static final double MOVE_STEP = 1.0D;
    private static final double FLY_SPEED = 0.4D;

    /** 仅服务端：手动悬停点 */
    private Vec3 hoverTarget = Vec3.ZERO;

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
    public boolean causeFallDamage(float fallDistance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource damageSource) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.BlockPos pos) {
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
        if (owner == null || !owner.isAlive()) {
            dropAsItem();
            this.discard();
            return;
        }

        int ticks = this.entityData.get(TAKEOFF_TICKS);
        if (ticks < Config.DRONE_TAKEOFF_DELAY.get()) {
            this.entityData.set(TAKEOFF_TICKS, ticks + 1);
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }

        if (this.entityData.get(MANUAL_CONTROL)) {
            Vec3 target = clampToOwner(owner, this.hoverTarget);
            flyToward(target, owner);
            faceMovementDirection(target);
        } else {
            flyToward(followPoint(owner), owner);
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

    /** 手动模式下镜头朝移动方向 */
    private void faceMovementDirection(Vec3 target) {
        Vec3 delta = target.subtract(this.position());
        if (delta.horizontalDistanceSqr() > 1.0E-4) {
            setYawSmooth((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        }
    }

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

    private void flyToward(Vec3 target, Player owner) {
        this.getNavigation().stop();
        Vec3 delta = target.subtract(this.position());
        double dist = delta.length();
        if (dist < 0.05D) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 step = delta.scale(Math.min(1.0D, FLY_SPEED / dist));
        this.setDeltaMovement(step);
        this.move(MoverType.SELF, step);
    }

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
