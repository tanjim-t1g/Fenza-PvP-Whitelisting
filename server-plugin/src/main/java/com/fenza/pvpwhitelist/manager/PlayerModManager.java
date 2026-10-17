package com.fenza.pvpwhitelist.manager;

import com.fenza.pvpwhitelist.FenzaPvPWhitelist;
import com.fenza.pvpwhitelist.webhook.DiscordWebhookManager;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerModManager {

    private final FenzaPvPWhitelist plugin;
    private final Map<UUID, List<String>> playerMods = new ConcurrentHashMap<>();
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

    /**
     * Loads tracked player names from config.yml into memory.
     */
    public void loadTrackedPlayers() {
        trackedPlayers.clear();
        List<String> list = plugin.getConfig().getStringList("tracked-players");
        for (String name : list) {
            if (name != null && !name.isBlank()) {
                trackedPlayers.add(name.trim().toLowerCase());
            }
        }
    }

    /**
     * Saves tracked players to config.yml.
     */
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
     * Sends the handshake packet to an online player over the fenza:sync channel.
     */
    public void triggerHandshake(Player player) {
        markPending(player);
        player.sendPluginMessage(plugin, FenzaPvPWhitelist.CHANNEL,
                "HANDSHAKE".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks if a mod entry is a known bundled library/language dependency
     * (e.g. Kotlin runtime, Quilt JSON parsers, MVEL expressions, Fabric API submodules).
     */
    public static boolean isLibraryMod(String mod) {
        String id = mod.contains(":") ? mod.split(":")[0].toLowerCase() : mod.toLowerCase();

        // Kotlin standard libraries, coroutines, datetime, serialization
        if (id.startsWith("org_jetbrains_kotlin")) return true;

        // Common config & parser libraries bundled via Jar-in-Jar
        if (id.startsWith("org_quiltmc_parsers")) return true;
        if (id.startsWith("org_mvel")) return true;
        if (id.startsWith("com_moandjiezana_toml")) return true;
        if (id.startsWith("com_electronwill_night-config")) return true;
        if (id.startsWith("net_bytebuddy")) return true;
        if (id.startsWith("org_ow2_asm")) return true;

        // Fabric API internal sub-modules (keep 'fabric-api' and 'fabricloader')
        if (id.startsWith("fabric-") && !id.equals("fabric-api") && !id.startsWith("fabric-language-")) {
            return true;
        }

        return false;
    }

    /**
     * Process the mod list response from the client mod.
     * Parses the JSON array, stores in memory, saves to disk file, sends Discord webhook, checks for banned mods.
     */
    public void handleModListResponse(Player player, String jsonData) {
        try {
            List<String> mods = parseModList(jsonData);
            playerMods.put(player.getUniqueId(), mods);
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

            // 1. Log to server console
            if (plugin.getConfig().getBoolean("log-mod-lists", true)) {
                plugin.getLogger().info("[ModList] " + player.getName() + " (" + userMods.size() + " mods, "
                        + libraryMods.size() + " libraries): " + userMods);
            }

            // 2. Save persistently to disk file (plugins/Fenza-PvP-Whitelisting/player-mods/<player>.txt)
            saveModsToFile(player, mods);

            // 3. Check for banned mods
            List<String> bannedModsConfig = plugin.getConfig().getStringList("banned-mods");
            List<String> bannedModsDetected = new ArrayList<>();
            if (!bannedModsConfig.isEmpty()) {
                for (String mod : mods) {
                    String modId = mod.contains(":") ? mod.split(":")[0].toLowerCase() : mod.toLowerCase();
                    if (bannedModsConfig.contains(modId)) {
                        bannedModsDetected.add(mod);
                    }
                }
            }

            // 4. Send to Discord Webhook
            webhookManager.sendModList(player, userMods, libraryMods, bannedModsDetected);

            // 5. If banned mods found, kick the player
            if (!bannedModsDetected.isEmpty()) {
                String firstBanned = bannedModsDetected.get(0);
                String kickMsg = plugin.getConfig().getString("kick-message-banned-mod",
                                "\u00a7cYou have a prohibited mod installed: %mod%")
                        .replace("%mod%", firstBanned);
                player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(kickMsg));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse mod list from " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Resends currently cached mods for an online verified player to the Discord webhook.
     */
    public void resendModsToWebhook(Player player) {
        List<String> mods = getPlayerMods(player);
        if (mods.isEmpty()) return;

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
        }

        webhookManager.sendModList(player, userMods, libraryMods, bannedModsDetected);
    }

    /**
     * Saves player's mod list to plugins/Fenza-PvP-Whitelisting/player-mods/<name>.txt
     * Separates actual user mods from bundled libraries for clear inspection.
     */
    private void saveModsToFile(Player player, List<String> mods) {
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
                writer.println("Actual Mods: " + userMods.size());
                writer.println("Bundled Libraries: " + libraryMods.size());
                writer.println("Total Detected: " + mods.size());
                writer.println("=================================================");
                writer.println();
                writer.println("=== ACTUAL USER MODS ===");
                for (String mod : userMods) {
                    writer.println(" - " + mod);
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

    /**
     * Loads a player's saved mods from disk if they are offline.
     */
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

    /**
     * Simple JSON array parser for format: ["modid:version", "modid:version", ...]
     */
    private List<String> parseModList(String json) {
        List<String> mods = new ArrayList<>();
        json = json.trim();
        if (json.startsWith("[") && json.endsWith("]")) {
            json = json.substring(1, json.length() - 1);
            if (!json.isEmpty()) {
                boolean inQuote = false;
                StringBuilder current = new StringBuilder();
                for (char c : json.toCharArray()) {
                    if (c == '"') {
                        inQuote = !inQuote;
                    } else if (c == ',' && !inQuote) {
                        String entry = current.toString().trim();
                        if (!entry.isEmpty()) mods.add(entry);
                        current = new StringBuilder();
                    } else {
                        current.append(c);
                    }
                }
                String last = current.toString().trim();
                if (!last.isEmpty()) mods.add(last);
            }
        }
        return mods;
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
        verifiedPlayers.remove(player.getUniqueId());
        pendingPlayers.remove(player.getUniqueId());
    }
}
