package com.fenza.pvpwhitelist.listener;

import com.fenza.pvpwhitelist.FenzaPvPWhitelist;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public class PlayerConnectionListener implements Listener, PluginMessageListener {

    private final FenzaPvPWhitelist plugin;

    public PlayerConnectionListener(FenzaPvPWhitelist plugin) {
        this.plugin = plugin;
    }

    /**
     * When a player joins:
     * - If check-specific-players-only is true and the player is not in tracked-players,
     *   we do NOTHING. No check is performed, no kick task is scheduled, they play freely.
     * - Only players in the tracked list are subjected to mod verification and kicked if missing.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // If specific players only is enabled, ignore anyone not on the list
        if (plugin.getModManager().isSpecificPlayersOnly() && !plugin.getModManager().isPlayerTracked(player)) {
            return;
        }

        plugin.getModManager().markPending(player);

        int timeout = plugin.getConfig().getInt("handshake-timeout", 10);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !plugin.getModManager().isVerified(player)) {
                    // Send alert to Discord webhook
                    plugin.getModManager().getWebhookManager().sendVerificationFailed(player, timeout);

                    String kickMsg = plugin.getConfig().getString("kick-message-no-mod",
                            "\u00a7cYou are missing a required mod to join this server.");
                    player.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize(kickMsg));
                }
            }
        }.runTaskLater(plugin, timeout * 20L);
    }

    /**
     * When the client registers our custom channel:
     * Only send the challenge handshake if the player is actually in the target list (or if specific players mode is off).
     */
    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent event) {
        if (!event.getChannel().equals(FenzaPvPWhitelist.CHANNEL)) return;

        Player player = event.getPlayer();
        if (plugin.getModManager().isSpecificPlayersOnly() && !plugin.getModManager().isPlayerTracked(player)) {
            // Not in the target list - do not perform any handshake or check
            return;
        }

        plugin.getModManager().triggerHandshake(player);
    }

    /**
     * Clean up player data when they disconnect.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getModManager().removePlayer(event.getPlayer());
    }

    /**
     * Handle incoming plugin messages from the client mod.
     * Only process responses for tracked players.
     */
    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(FenzaPvPWhitelist.CHANNEL)) return;

        if (plugin.getModManager().isSpecificPlayersOnly() && !plugin.getModManager().isPlayerTracked(player)) {
            // Untracked player - ignore
            return;
        }

        String data = new String(message, StandardCharsets.UTF_8);
        plugin.getModManager().handleModListResponse(player, data);
    }
}
