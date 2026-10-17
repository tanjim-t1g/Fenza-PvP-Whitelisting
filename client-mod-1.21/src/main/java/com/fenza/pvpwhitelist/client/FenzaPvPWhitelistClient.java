package com.fenza.pvpwhitelist.client;

import com.fenza.pvpwhitelist.client.network.FenzaPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Client-side mod initializer for Fenza PvP Whitelisting (Minecraft 1.21.x).
 * <p>
 * Incorporates cryptographic challenge-response verification, bytecode self-hashing,
 * JVM cheat class inspection, and physical mods folder scanning to prevent tampering or bypasses.
 */
public class FenzaPvPWhitelistClient implements ClientModInitializer {

    public static final String PROTOCOL_VERSION = "2.0.0";
    public static final String INTEGRITY_SALT = "Fenza_PvP_AntiTamper_v2_Key";

    private static final List<String> KNOWN_CHEAT_CLASSES = List.of(
            "meteordevelopment.meteorclient.MeteorClient",
            "net.wurstclient.WurstClient",
            "net.wurstclient.WurstMod",
            "org.rusherhack.core.RusherHack",
            "me.earth.phobos.Phobos",
            "me.earth.earthhack.impl.Earthhack",
            "bleach.hack.BleachHack",
            "net.ccbluex.liquidbounce.LiquidBounce",
            "me.flourick.fvt.FVT",
            "me.zeroeightsix.kami.KamiMod",
            "me.travis.wurstplus.WurstPlus",
            "com.lukflug.panelstudio.PanelStudio",
            "me.rebirthclient.Rebirth"
    );

    @Override
    public void onInitializeClient() {
        // Register the payload type for both directions (1.21.x API)
        PayloadTypeRegistry.playS2C().register(FenzaPayload.PACKET_ID, FenzaPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FenzaPayload.PACKET_ID, FenzaPayload.CODEC);

        // Listen for server handshake requests
        ClientPlayNetworking.registerGlobalReceiver(FenzaPayload.PACKET_ID, (payload, context) -> {
            String message = new String(payload.data(), StandardCharsets.UTF_8);

            if (message.startsWith("HANDSHAKE")) {
                String nonce = "";
                if (message.contains(":")) {
                    nonce = message.substring(message.indexOf(":") + 1).trim();
                }

                String responseJson = buildVerificationPayload(nonce);
                byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                context.responseSender().sendPacket(new FenzaPayload(responseBytes));
            }
        });
    }

    /**
     * Builds the complete integrity verification response payload.
     */
    private String buildVerificationPayload(String nonce) {
        // 1. Calculate class bytecode SHA-256
        String classHash = computeClassBytecodeHash();

        // 2. Generate HMAC signature with server nonce
        String signature = computeSignature(classHash, nonce);

        // 3. Collect installed Fabric mods
        List<String> mods = collectFabricMods();

        // 4. Scan physical .jar files in mods folder
        List<String> modFiles = scanModsFolderFiles();

        // 5. Scan JVM for active cheat class injections
        List<String> detectedCheats = detectCheatClasses();

        // 6. Build JSON payload
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"version\":\"").append(PROTOCOL_VERSION).append("\",");
        sb.append("\"nonce\":\"").append(escapeJson(nonce)).append("\",");
        sb.append("\"classHash\":\"").append(classHash).append("\",");
        sb.append("\"signature\":\"").append(signature).append("\",");

        // mods array
        sb.append("\"mods\":[");
        for (int i = 0; i < mods.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(mods.get(i))).append("\"");
        }
        sb.append("],");

        // modFiles array
        sb.append("\"modFiles\":[");
        for (int i = 0; i < modFiles.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(modFiles.get(i))).append("\"");
        }
        sb.append("],");

        // detectedCheats array
        sb.append("\"detectedCheats\":[");
        for (int i = 0; i < detectedCheats.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(detectedCheats.get(i))).append("\"");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    /**
     * Reads in-memory class bytecode of this class and hashes it with SHA-256.
     * If anyone edits or recompiles this class, the hash changes completely.
     */
    private String computeClassBytecodeHash() {
        try {
            String path = "com/fenza/pvpwhitelist/client/FenzaPvPWhitelistClient.class";
            InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is != null) {
                try (is) {
                    byte[] bytes = is.readAllBytes();
                    return sha256Hex(bytes);
                }
            }
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }

    /**
     * Computes cryptographic signature using classHash, server nonce, and integrity salt.
     */
    private String computeSignature(String classHash, String nonce) {
        String data = classHash + ":" + nonce + ":" + INTEGRITY_SALT;
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Scans JVM memory for active cheat class injections.
     */
    private List<String> detectCheatClasses() {
        List<String> found = new ArrayList<>();
        ClassLoader cl = getClass().getClassLoader();
        for (String className : KNOWN_CHEAT_CLASSES) {
            try {
                Class.forName(className, false, cl);
                found.add(className);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return found;
    }

    /**
     * Scans the physical .minecraft/mods directory for all .jar filenames.
     */
    private List<String> scanModsFolderFiles() {
        List<String> files = new ArrayList<>();
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            if (Files.exists(modsDir) && Files.isDirectory(modsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
                    for (Path p : stream) {
                        files.add(p.getFileName().toString());
                    }
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(files);
        return files;
    }

    /**
     * Collects all installed Fabric mods (modid:version).
     */
    private List<String> collectFabricMods() {
        Collection<ModContainer> allMods = FabricLoader.getInstance().getAllMods();
        List<String> list = new ArrayList<>();
        for (ModContainer mod : allMods) {
            ModMetadata meta = mod.getMetadata();
            String id = meta.getId();
            if ("java".equals(id) || "minecraft".equals(id)) {
                continue;
            }
            list.add(id + ":" + meta.getVersion().getFriendlyString());
        }
        Collections.sort(list);
        return list;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
