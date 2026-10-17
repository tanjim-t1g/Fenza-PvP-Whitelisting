package com.fenza.pvpwhitelist.manager;

import com.fenza.pvpwhitelist.FenzaPvPWhitelist;
import com.fenza.pvpwhitelist.webhook.DiscordWebhookManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerModManager {

    public static final String INTEGRITY_SALT = "Fenza_PvP_AntiTamper_v2_Key";

    private final FenzaPvPWhitelist plugin;
    private final Map<UUID, List<String>> playerMods = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> playerModFiles = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerClassHashes = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNonces = new ConcurrentHashMap<>();
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> trackedPlayers = ConcurrentHashMap.newKeySet();
    private final File dataFolder;
    private final DiscordWebhookManager webhookManager;

    public PlayerModManager(FenzaPvPWhitelist plugin) {
        this.plugin = plugin;
        this.webhookManager = new DiscordWebhookManager(plugin);
        this.dataFolder = new File(plugin.getDataFolder(), "player-mods");
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }

        loadTrackedPlayers();
    }

    public void setPlayerNonce(UUID uuid, String nonce) {
        playerNonces.put(uuid, nonce);
    }

    public String getPlayerNonce(UUID uuid) {
        return playerNonces.get(uuid);
    }

    public void removePlayerNonce(UUID uuid) {
        playerNonces.remove(uuid);
    }

    public void loadTrackedPlayers() {
        trackedPlayers.clear();
        List<String> list = plugin.getConfig().getStringList("tracked-players");
        for (String name : list) {
            if (name != null && !name.isBlank()) {
                trackedPlayers.add(name.trim().toLowerCase());
            }
        }
    }

    public synchronized void saveTrackedPlayers() {
        List<String> list = new ArrayList<>(trackedPlayers);
        Collections.sort(list);
        plugin.getConfig().set("tracked-players", list);
        plugin.saveConfig();
    }

    public boolean isSpecificPlayersOnly() {
        return plugin.getConfig().getBoolean("check-specific-players-only", true);
    }

    public boolean isPlayerTracked(String name) {
        if (name == null) return false;
        return trackedPlayers.contains(name.trim().toLowerCase());
    }

    public boolean isPlayerTracked(Player player) {
        if (player == null) return false;
        return isPlayerTracked(player.getName());
    }

    public boolean addTrackedPlayer(String name) {
        if (name == null || name.isBlank()) return false;
        boolean added = trackedPlayers.add(name.trim().toLowerCase());
        if (added) {
            saveTrackedPlayers();
        }
        return added;
    }

    public boolean removeTrackedPlayer(String name) {
        if (name == null) return false;
        boolean removed = trackedPlayers.remove(name.trim().toLowerCase());
        if (removed) {
            saveTrackedPlayers();
        }
        return removed;
    }

    public Set<String> getTrackedPlayers() {
        return Collections.unmodifiableSet(trackedPlayers);
    }

    public DiscordWebhookManager getWebhookManager() {
        return webhookManager;
    }

    public void markPending(Player player) {
        pendingPlayers.add(player.getUniqueId());
    }

    public boolean isVerified(Player player) {
        return verifiedPlayers.contains(player.getUniqueId());
    }

    public boolean isPending(Player player) {
        return pendingPlayers.contains(player.getUniqueId());
    }

    /**
     * Sends the challenge handshake packet to an online player over fenza:sync.
     */
    public void triggerHandshake(Player player) {
        markPending(player);
        String nonce = UUID.randomUUID().toString();
        setPlayerNonce(player.getUniqueId(), nonce);
        player.sendPluginMessage(plugin, FenzaPvPWhitelist.CHANNEL,
                ("HANDSHAKE:" + nonce).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isLibraryMod(String mod) {
        String id = mod.contains(":") ? mod.split(":")[0].toLowerCase() : mod.toLowerCase();

        if (id.startsWith("org_jetbrains_kotlin")) return true;
        if (id.startsWith("org_quiltmc_parsers")) return true;
        if (id.startsWith("org_mvel")) return true;
        if (id.startsWith("com_moandjiezana_toml")) return true;
        if (id.startsWith("com_electronwill_night-config")) return true;
        if (id.startsWith("net_bytebuddy")) return true;
        if (id.startsWith("org_ow2_asm")) return true;

        if (id.startsWith("fabric-") && !id.equals("fabric-api") && !id.startsWith("fabric-language-")) {
            return true;
        }

        return false;
    }

    /**
     * Handles and cryptographically verifies the response from the client mod.
     */
    public void handleModListResponse(Player player, String jsonData) {
        try {
            jsonData = jsonData.trim();

            if (!jsonData.startsWith("{")) {
                if (plugin.getConfig().getBoolean("integrity-check.require-signature", true)) {
                    webhookManager.sendTamperAlert(player, "Unsigned/Legacy Client Mod (Missing Cryptographic Signature)", "", Collections.emptyList());
                    String kickMsg = plugin.getConfig().getString("integrity-check.kick-message-tampered",
                            "§c[Fenza] Client mod integrity verification failed!\n§7Your client mod has been altered, modified, or is outdated.");
                    player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize(kickMsg));
                    return;
                }
            }

            JsonObject root = JsonParser.parseString(jsonData).getAsJsonObject();

            String nonce = root.has("nonce") ? root.get("nonce").getAsString() : "";
            String classHash = root.has("classHash") ? root.get("classHash").getAsString() : "";
            String signature = root.has("signature") ? root.get("signature").getAsString() : "";

            // 1. Verify Nonce to prevent packet replay attacks
            String expectedNonce = getPlayerNonce(player.getUniqueId());
            if (expectedNonce != null && !expectedNonce.equals(nonce)) {
                webhookManager.sendTamperAlert(player, "Nonce Mismatch (Replay Attack or Forged Packet)", classHash, Collections.emptyList());
                String kickMsg = plugin.getConfig().getString("integrity-check.kick-message-tampered",
                        "§c[Fenza] Client mod integrity verification failed!\n§7Invalid handshake session detected.");
                player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(kickMsg));
                return;
            }

            // 2. Verify Cryptographic HMAC Signature
            String expectedSig = sha256Hex(classHash + ":" + nonce + ":" + INTEGRITY_SALT);
            if (!signature.equalsIgnoreCase(expectedSig)) {
                webhookManager.sendTamperAlert(player, "Signature Validation Failed (Code Tampering)", classHash, Collections.emptyList());
                String kickMsg = plugin.getConfig().getString("integrity-check.kick-message-tampered",
                        "§c[Fenza] Client mod integrity verification failed!\n§7Your client mod has been altered or modified.");
                player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(kickMsg));
                return;
            }

            // 3. Verify Class Bytecode Hash against official builds
            if (plugin.getConfig().getBoolean("integrity-check.enforce-class-hash", true)) {
                List<String> allowedHashes = plugin.getConfig().getStringList("integrity-check.allowed-class-hashes");
                boolean hashMatched = false;
                for (String allowed : allowedHashes) {
                    if (allowed.equalsIgnoreCase(classHash)) {
                        hashMatched = true;
                        break;
                    }
                }
                if (!allowedHashes.isEmpty() && !hashMatched) {
                    webhookManager.sendTamperAlert(player, "Unauthorized/Modified Class Bytecode (Recompiled Mod)", classHash, Collections.emptyList());
                    String kickMsg = plugin.getConfig().getString("integrity-check.kick-message-tampered",
                            "§c[Fenza] Client mod integrity verification failed!\n§7Unauthorized or recompiled mod detected.");
                    player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize(kickMsg));
                    return;
                }
            }

            // 4. Verify JVM Injected Cheat Classes
            List<String> detectedCheats = new ArrayList<>();
            if (root.has("detectedCheats") && root.get("detectedCheats").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("detectedCheats")) {
                    detectedCheats.add(el.getAsString());
                }
            }
            if (!detectedCheats.isEmpty()) {
                webhookManager.sendTamperAlert(player, "Prohibited Injected Cheat Classes Active in JVM", classHash, detectedCheats);
                String kickMsg = plugin.getConfig().getString("kick-message-banned-mod",
                                "§cYou have a prohibited mod installed: %mod%")
                        .replace("%mod%", detectedCheats.get(0));
                player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(kickMsg));
                return;
            }

            // 5. Parse Mods and Mod Files
            List<String> mods = new ArrayList<>();
            if (root.has("mods") && root.get("mods").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("mods")) {
                    mods.add(el.getAsString());
                }
            }

            List<String> modFiles = new ArrayList<>();
            if (root.has("modFiles") && root.get("modFiles").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("modFiles")) {
                    modFiles.add(el.getAsString());
                }
            }

            playerMods.put(player.getUniqueId(), mods);
            playerModFiles.put(player.getUniqueId(), modFiles);
            playerClassHashes.put(player.getUniqueId(), classHash);
            verifiedPlayers.add(player.getUniqueId());
            pendingPlayers.remove(player.getUniqueId());

            List<String> userMods = new ArrayList<>();
            List<String> libraryMods = new ArrayList<>();
            for (String mod : mods) {
                if (isLibraryMod(mod)) {
                    libraryMods.add(mod);
                } else {
                    userMods.add(mod);
                }
            }

            // 6. Check Banned Mods against both mod IDs and physical .jar filenames
            List<String> bannedModsConfig = plugin.getConfig().getStringList("banned-mods");
            List<String> bannedModsDetected = new ArrayList<>();
            if (!bannedModsConfig.isEmpty()) {
                for (String mod : mods) {
                    String modId = mod.contains(":") ? mod.split(":")[0].toLowerCase() : mod.toLowerCase();
                    if (bannedModsConfig.contains(modId)) {
                        bannedModsDetected.add(mod);
                    }
                }
                for (String file : modFiles) {
                    String lowerFile = file.toLowerCase();
                    for (String banned : bannedModsConfig) {
                        if (lowerFile.contains(banned.toLowerCase()) && !bannedModsDetected.contains(file)) {
                            bannedModsDetected.add("File: " + file);
                        }
                    }
                }
            }

            // 7. Log to console
            if (plugin.getConfig().getBoolean("log-mod-lists", true)) {
                plugin.getLogger().info("[ModList] " + player.getName() + " [AUTHENTIC] (" + userMods.size() + " mods, "
                        + libraryMods.size() + " libraries, " + modFiles.size() + " jars): " + userMods);
            }

            // 8. Save to disk file
            saveModsToFile(player, mods, modFiles, classHash);

            // 9. Send Discord webhook
            webhookManager.sendModList(player, userMods, libraryMods, modFiles, bannedModsDetected, classHash);

            // 10. If banned mods found, kick
            if (!bannedModsDetected.isEmpty()) {
                String firstBanned = bannedModsDetected.get(0);
                String kickMsg = plugin.getConfig().getString("kick-message-banned-mod",
                                "§cYou have a prohibited mod installed: %mod%")
                        .replace("%mod%", firstBanned);
                player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(kickMsg));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse verification response from " + player.getName() + ": " + e.getMessage());
        }
    }

    public void resendModsToWebhook(Player player) {
        List<String> mods = getPlayerMods(player);
        if (mods.isEmpty()) return;

        List<String> modFiles = playerModFiles.getOrDefault(player.getUniqueId(), Collections.emptyList());
        String classHash = playerClassHashes.getOrDefault(player.getUniqueId(), "UNKNOWN");

        List<String> userMods = new ArrayList<>();
        List<String> libraryMods = new ArrayList<>();
        for (String mod : mods) {
            if (isLibraryMod(mod)) {
                libraryMods.add(mod);
            } else {
                userMods.add(mod);
            }
        }

        List<String> bannedModsConfig = plugin.getConfig().getStringList("banned-mods");
        List<String> bannedModsDetected = new ArrayList<>();
        if (!bannedModsConfig.isEmpty()) {
            for (String mod : mods) {
                String modId = mod.contains(":") ? mod.split(":")[0].toLowerCase() : mod.toLowerCase();
                if (bannedModsConfig.contains(modId)) {
                    bannedModsDetected.add(mod);
                }
            }
            for (String file : modFiles) {
                String lowerFile = file.toLowerCase();
                for (String banned : bannedModsConfig) {
                    if (lowerFile.contains(banned.toLowerCase()) && !bannedModsDetected.contains(file)) {
                        bannedModsDetected.add("File: " + file);
                    }
                }
            }
        }

        webhookManager.sendModList(player, userMods, libraryMods, modFiles, bannedModsDetected, classHash);
    }

    private void saveModsToFile(Player player, List<String> mods, List<String> modFiles, String classHash) {
        try {
            File playerFile = new File(dataFolder, player.getName().toLowerCase() + ".txt");
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            List<String> userMods = new ArrayList<>();
            List<String> libraryMods = new ArrayList<>();
            for (String mod : mods) {
                if (isLibraryMod(mod)) {
                    libraryMods.add(mod);
                } else {
                    userMods.add(mod);
                }
            }

            try (PrintWriter writer = new PrintWriter(new FileWriter(playerFile, false))) {
                writer.println("=================================================");
                writer.println("Player: " + player.getName());
                writer.println("UUID: " + player.getUniqueId());
                writer.println("Recorded At: " + timestamp);
                writer.println("Integrity Status: AUTHENTIC / VERIFIED");
                writer.println("Bytecode SHA-256: " + classHash);
                writer.println("Actual Mods: " + userMods.size());
                writer.println("Bundled Libraries: " + libraryMods.size());
                writer.println("Physical Mod Jars: " + modFiles.size());
                writer.println("Total Detected: " + mods.size());
                writer.println("=================================================");
                writer.println();
                writer.println("=== ACTUAL USER MODS ===");
                for (String mod : userMods) {
                    writer.println(" - " + mod);
                }
                writer.println();
                writer.println("=== PHYSICAL .JAR FILES IN MODS/ FOLDER ===");
                for (String f : modFiles) {
                    writer.println(" 📁 " + f);
                }
                writer.println();
                writer.println("=== BUNDLED LIBRARIES & DEPENDENCIES ===");
                for (String mod : libraryMods) {
                    writer.println(" - " + mod);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save mod file for " + player.getName() + ": " + e.getMessage());
        }
    }

    public List<String> getOfflinePlayerMods(String playerName) {
        File playerFile = new File(dataFolder, playerName.toLowerCase() + ".txt");
        if (!playerFile.exists()) {
            return Collections.emptyList();
        }

        try {
            List<String> lines = Files.readAllLines(playerFile.toPath());
            List<String> mods = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith(" - ")) {
                    mods.add(line.substring(3).trim());
                }
            }
            return mods;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<String> getPlayerMods(Player player) {
        return playerMods.getOrDefault(player.getUniqueId(), Collections.emptyList());
    }

    public List<String> getPlayerMods(UUID uuid) {
        return playerMods.getOrDefault(uuid, Collections.emptyList());
    }

    public File getDataFolder() {
        return dataFolder;
    }

    public void removePlayer(Player player) {
        playerMods.remove(player.getUniqueId());
        playerModFiles.remove(player.getUniqueId());
        playerClassHashes.remove(player.getUniqueId());
        playerNonces.remove(player.getUniqueId());
        verifiedPlayers.remove(player.getUniqueId());
        pendingPlayers.remove(player.getUniqueId());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
