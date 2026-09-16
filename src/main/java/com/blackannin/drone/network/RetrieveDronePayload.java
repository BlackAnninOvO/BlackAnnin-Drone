package com.blackannin.drone.network;

import com.blackannin.drone.BlackAnninsDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RetrieveDronePayload(int entityId) implements CustomPacketPayload {
    public static final Type<RetrieveDronePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BlackAnninsDrone.MODID, "retrieve_drone"));

    public static final StreamCodec<FriendlyByteBuf, RetrieveDronePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RetrieveDronePayload::entityId,
            RetrieveDronePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
