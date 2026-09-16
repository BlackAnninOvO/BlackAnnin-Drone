package com.blackannin.drone.network;

import com.blackannin.drone.BlackAnninsDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DroneControlPayload(byte action) implements CustomPacketPayload {
    public static final byte FOLLOW = 0;
    public static final byte FORWARD = 1;
    public static final byte BACK = 2;
    public static final byte LEFT = 3;
    public static final byte RIGHT = 4;
    public static final byte UP = 5;
    public static final byte DOWN = 6;

    public static final Type<DroneControlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BlackAnninsDrone.MODID, "drone_control"));

    public static final StreamCodec<FriendlyByteBuf, DroneControlPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, DroneControlPayload::action,
            DroneControlPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
