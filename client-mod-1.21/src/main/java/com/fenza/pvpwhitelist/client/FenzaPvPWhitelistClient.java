package com.fenza.pvpwhitelist.client;

import com.fenza.pvpwhitelist.client.network.FenzaPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Client-side mod initializer for Fenza PvP Whitelisting (1.21.x).
 * <p>
 * Silently listens for a handshake from the server plugin on the
 * {@code fenza:sync} channel. When received, it collects the list
 * of all installed Fabric mods and sends them back as a JSON array.
 * <p>
 * No GUI, no chat messages, no toasts — completely invisible to the player.
 */
public class FenzaPvPWhitelistClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register the payload type for both directions (1.21.x API)
        PayloadTypeRegistry.playS2C().register(FenzaPayload.PACKET_ID, FenzaPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FenzaPayload.PACKET_ID, FenzaPayload.CODEC);

        // Listen for server handshake requests
        ClientPlayNetworking.registerGlobalReceiver(FenzaPayload.PACKET_ID, (payload, context) -> {
            String message = new String(payload.data(), StandardCharsets.UTF_8);

            if ("HANDSHAKE".equals(message)) {
                // Collect all installed mods and send back
                byte[] response = collectModList().getBytes(StandardCharsets.UTF_8);
                context.responseSender().sendPacket(new FenzaPayload(response));
            }
        });
    }

    /**
     * Collects all installed Fabric mods and returns them as a JSON array string.
     * Format: ["modid:version", "modid:version", ...]
     */
    private String collectModList() {
        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        for (ModContainer mod : mods) {
            ModMetadata meta = mod.getMetadata();
            // Skip built-in mods like "java" and "minecraft" for cleaner output
            String id = meta.getId();
            if ("java".equals(id) || "minecraft".equals(id)) {
                continue;
            }

            if (!first) {
                sb.append(",");
            }
            sb.append("\"")
              .append(escapeJson(id))
              .append(":")
              .append(escapeJson(meta.getVersion().getFriendlyString()))
              .append("\"");
            first = false;
        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * Escape special characters for JSON string safety.
     */
    private String escapeJson(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
