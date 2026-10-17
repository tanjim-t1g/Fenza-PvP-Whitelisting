package com.fenza.pvpwhitelist.webhook;

import com.fenza.pvpwhitelist.FenzaPvPWhitelist;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Manages sending real-time mod verification results and alerts to a Discord webhook.
 */
public class DiscordWebhookManager {

    private final FenzaPvPWhitelist plugin;
    private final HttpClient httpClient;

    public DiscordWebhookManager(FenzaPvPWhitelist plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Sends the collected mod list for a player to the configured Discord webhook.
     */
    public void sendModList(Player player, List<String> userMods, List<String> libraryMods, List<String> bannedModsDetected) {
        String webhookUrl = plugin.getConfig().getString("discord-webhook.url", "");
        boolean enabled = plugin.getConfig().getBoolean("discord-webhook.enabled", true);

        if (!enabled || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("username", "Fenza ModCheck");

            JsonArray embeds = new JsonArray();
            JsonObject embed = new JsonObject();

            boolean hasBanned = !bannedModsDetected.isEmpty();
            embed.addProperty("title", (hasBanned ? "🚨 Prohibited Mod Detected: " : "📋 Mod Verification: ") + player.getName());
            // Green: 0x2ECC71 (3066993) | Red: 0xE74C3C (15158332)
            embed.addProperty("color", hasBanned ? 15158332 : 3066993);

            JsonArray fields = new JsonArray();

            // Field: Player
            JsonObject fPlayer = new JsonObject();
            fPlayer.addProperty("name", "Player");
            fPlayer.addProperty("value", "**" + player.getName() + "** (`" + player.getUniqueId() + "`)");
            fPlayer.addProperty("inline", false);
            fields.add(fPlayer);

            // Field: Status
            JsonObject fStatus = new JsonObject();
            fStatus.addProperty("name", "Status");
            if (hasBanned) {
                fStatus.addProperty("value", "❌ **BANNED MOD FOUND**\n" + String.join(", ", bannedModsDetected));
            } else {
                fStatus.addProperty("value", "✅ Verified (Fenza Companion Mod Installed)");
            }
            fStatus.addProperty("inline", true);
            fields.add(fStatus);

            // Field: Counts
            JsonObject fCounts = new JsonObject();
            fCounts.addProperty("name", "Mod Breakdown");
            fCounts.addProperty("value", "**" + userMods.size() + "** Gameplay Mods\n" + libraryMods.size() + " Libraries (Hidden)");
            fCounts.addProperty("inline", true);
            fields.add(fCounts);

            // Field(s): User Gameplay Mods (chunked into <= 950 characters)
            if (userMods.isEmpty()) {
                JsonObject fMods = new JsonObject();
                fMods.addProperty("name", "Installed Gameplay Mods");
                fMods.addProperty("value", "_None (Vanilla / Fabric API only)_");
                fMods.addProperty("inline", false);
                fields.add(fMods);
            } else {
                StringBuilder currentChunk = new StringBuilder();
                int part = 1;
                for (String mod : userMods) {
                    String line = "• `" + mod + "`\n";
                    if (currentChunk.length() + line.length() > 950) {
                        JsonObject fMods = new JsonObject();
                        fMods.addProperty("name", part == 1 ? "Installed Gameplay Mods" : "Installed Gameplay Mods (Part " + part + ")");
                        fMods.addProperty("value", currentChunk.toString());
                        fMods.addProperty("inline", false);
                        fields.add(fMods);
                        currentChunk = new StringBuilder();
                        part++;
                    }
                    currentChunk.append(line);
                }
                if (currentChunk.length() > 0) {
                    JsonObject fMods = new JsonObject();
                    fMods.addProperty("name", part == 1 ? "Installed Gameplay Mods" : "Installed Gameplay Mods (Part " + part + ")");
                    fMods.addProperty("value", currentChunk.toString());
                    fMods.addProperty("inline", false);
                    fields.add(fMods);
                }
            }

            embed.add("fields", fields);

            JsonObject footer = new JsonObject();
            footer.addProperty("text", "Fenza PvP Whitelisting • " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
            embed.add("footer", footer);

            embeds.add(embed);
            payload.add("embeds", embeds);

            sendAsync(webhookUrl, payload.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("Error building Discord webhook embed: " + e.getMessage());
        }
    }

    /**
     * Sends an alert when a tracked player is kicked for not having the mod.
     */
    public void sendVerificationFailed(Player player, int timeoutSeconds) {
        String webhookUrl = plugin.getConfig().getString("discord-webhook.url", "");
        boolean enabled = plugin.getConfig().getBoolean("discord-webhook.enabled", true);
        boolean alertMissing = plugin.getConfig().getBoolean("discord-webhook.alert-on-missing-mod", true);

        if (!enabled || !alertMissing || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("username", "Fenza ModCheck");

            JsonArray embeds = new JsonArray();
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "⚠️ Missing Required Mod: " + player.getName());
            embed.addProperty("color", 15158332); // Red

            JsonArray fields = new JsonArray();

            JsonObject fPlayer = new JsonObject();
            fPlayer.addProperty("name", "Player");
            fPlayer.addProperty("value", "**" + player.getName() + "** (`" + player.getUniqueId() + "`)");
            fPlayer.addProperty("inline", true);
            fields.add(fPlayer);

            JsonObject fAction = new JsonObject();
            fAction.addProperty("name", "Action Taken");
            fAction.addProperty("value", "🚫 **Kicked from server**");
            fAction.addProperty("inline", true);
            fields.add(fAction);

            JsonObject fReason = new JsonObject();
            fReason.addProperty("name", "Reason");
            fReason.addProperty("value", "Did not respond to Fenza verification handshake within " + timeoutSeconds + "s. The required client mod is either missing or disabled.");
            fReason.addProperty("inline", false);
            fields.add(fReason);

            embed.add("fields", fields);

            JsonObject footer = new JsonObject();
            footer.addProperty("text", "Fenza PvP Whitelisting • " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
            embed.add("footer", footer);

            embeds.add(embed);
            payload.add("embeds", embeds);

            sendAsync(webhookUrl, payload.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("Error building Discord missing-mod webhook embed: " + e.getMessage());
        }
    }

    private void sendAsync(String webhookUrl, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", "Fenza-PvP-Whitelisting")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        plugin.getLogger().warning("Discord webhook returned HTTP " + response.statusCode() + ": " + response.body());
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to send Discord webhook: " + ex.getMessage());
                    return null;
                });
    }
}
