package com.blackannin.drone.network;

import com.blackannin.drone.BlackAnninsDrone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 遥控器输入包：操控界面打开期间每个客户端 tick 发送一次。
 *
 * @param forward    前进输入 [-1,1]
 * @param strafe     左右平移输入 [-1,1]
 * @param up         升降输入 [-1,1]
 * @param yawDelta   本次累计的视角水平增量（鼠标像素或摇杆当量）
 * @param pitchDelta 本次累计的视角垂直增量
 * @param flags      bit0 = 恢复跟随（退出手动模式并重置视角），bit1 = 接管手动模式，bit2 = 重置视角，bit3 = 切换自动运镜
 */
public record RemoteControlPayload(float forward, float strafe, float up,
                                   float yawDelta, float pitchDelta, byte flags) implements CustomPacketPayload {
    public static final byte FLAG_FOLLOW = 1;
    public static final byte FLAG_TAKEOVER = 2;
    public static final byte FLAG_RESET_VIEW = 4;
    public static final byte FLAG_CINEMA = 8;

    public static final Type<RemoteControlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BlackAnninsDrone.MODID, "remote_control"));

    public static final StreamCodec<FriendlyByteBuf, RemoteControlPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, RemoteControlPayload::forward,
            ByteBufCodecs.FLOAT, RemoteControlPayload::strafe,
            ByteBufCodecs.FLOAT, RemoteControlPayload::up,
            ByteBufCodecs.FLOAT, RemoteControlPayload::yawDelta,
            ByteBufCodecs.FLOAT, RemoteControlPayload::pitchDelta,
            ByteBufCodecs.BYTE, RemoteControlPayload::flags,
            RemoteControlPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
