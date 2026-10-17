package com.fenza.pvpwhitelist.client.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Raw-byte custom payload for the fenza:sync channel.
 * Compatible with Paper's sendPluginMessage() which sends raw bytes.
 * Uses official unobfuscated Mojang classes for Minecraft 26.1+.
 */
public record FenzaPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FenzaPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("fenza", "sync"));

    public static final StreamCodec<ByteBuf, FenzaPayload> STREAM_CODEC =
            CustomPacketPayload.codec(FenzaPayload::write, FenzaPayload::new);

    public FenzaPayload(ByteBuf buf) {
        this(readBytes(buf));
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    public void write(ByteBuf buf) {
        buf.writeBytes(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
