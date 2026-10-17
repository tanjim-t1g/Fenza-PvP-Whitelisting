package com.fenza.pvpwhitelist.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Raw-byte custom payload for the fenza:sync channel.
 * Target: Minecraft 1.21.x (1.21 - 1.21.4).
 */
public record FenzaPayload(byte[] data) implements CustomPayload {

    public static final Id<FenzaPayload> PACKET_ID =
            new Id<>(Identifier.of("fenza", "sync"));

    public static final PacketCodec<PacketByteBuf, FenzaPayload> CODEC =
            new PacketCodec<>() {
                @Override
                public FenzaPayload decode(PacketByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new FenzaPayload(bytes);
                }

                @Override
                public void encode(PacketByteBuf buf, FenzaPayload payload) {
                    buf.writeBytes(payload.data());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }
}
