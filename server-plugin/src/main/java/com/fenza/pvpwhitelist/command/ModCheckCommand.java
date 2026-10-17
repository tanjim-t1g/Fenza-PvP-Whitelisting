package com.fenza.pvpwhitelist.command;

import com.fenza.pvpwhitelist.FenzaPvPWhitelist;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ModCheckCommand implements CommandExecutor, TabCompleter {

    private final FenzaPvPWhitelist plugin;

    public ModCheckCommand(FenzaPvPWhitelist plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission("fenza.modcheck")) {
            sender.sendMessage("\u00a7cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("\u00a76=== Fenza ModCheck ===");
            sender.sendMessage("\u00a7e/modcheck <player> \u00a77- Add a player to the mod check list");
            sender.sendMessage("\u00a7e/modcheck remove <player> \u00a77- Remove a player from the list");
            sender.sendMessage("\u00a7e/modcheck list \u00a77- View all tracked players");
            return true;
        }

        String sub = args[0];

        // Subcommand: list
        if (sub.equalsIgnoreCase("list")) {
            Set<String> tracked = plugin.getModManager().getTrackedPlayers();
            if (tracked.isEmpty()) {
                sender.sendMessage("\u00a7eThe mod check list is currently empty. Use \u00a7f/modcheck <player> \u00a7eto add someone.");
                return true;
            }

            sender.sendMessage("\u00a76=== ModCheck Tracked Players (\u00a7e" + tracked.size() + "\u00a76) ===");
            for (String name : tracked) {
                Player online = Bukkit.getPlayerExact(name);
                String status;
                if (online != null && online.isOnline()) {
                    if (plugin.getModManager().isVerified(online)) {
                        status = "\u00a7a[ONLINE - VERIFIED]";
                    } else if (plugin.getModManager().isPending(online)) {
                        status = "\u00a7e[ONLINE - PENDING HANDSHAKE]";
                    } else {
                        status = "\u00a7c[ONLINE - UNVERIFIED]";
                    }
                } else {
                    status = "\u00a77[OFFLINE]";
                }
                sender.sendMessage("\u00a77 - \u00a7f" + name + " " + status);
            }
            return true;
        }

        // Subcommand: remove <player>
        if (sub.equalsIgnoreCase("remove")) {
            if (args.length < 2) {
                sender.sendMessage("\u00a7cUsage: /modcheck remove <player>");
                return true;
            }
            String targetName = args[1];
            if (!plugin.getModManager().isPlayerTracked(targetName)) {
                sender.sendMessage("\u00a7c" + targetName + " is not currently on the mod check list.");
                return true;
            }
            plugin.getModManager().removeTrackedPlayer(targetName);
            sender.sendMessage("\u00a7aSuccessfully removed \u00a7e" + targetName + " \u00a7afrom the mod check list.");
            return true;
        }

        // Subcommand: add <player> or direct /modcheck <player>
        String targetName = sub.equalsIgnoreCase("add") ? (args.length >= 2 ? args[1] : null) : sub;
        if (targetName == null || targetName.isBlank()) {
            sender.sendMessage("\u00a7cUsage: /modcheck <player>");
            return true;
        }

        if (plugin.getModManager().isPlayerTracked(targetName)) {
            sender.sendMessage("\u00a7e" + targetName + " \u00a7cis already on the mod check list! (Use \u00a7e/modcheck remove " + targetName + "\u00a7c to stop tracking)");
            return true;
        }

        plugin.getModManager().addTrackedPlayer(targetName);
        sender.sendMessage("\u00a7aAdded \u00a7e" + targetName + " \u00a7ato the mod check list.");

        // Check if player is currently online
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        if (onlineTarget != null && onlineTarget.isOnline()) {
            if (plugin.getModManager().isVerified(onlineTarget)) {
                sender.sendMessage("\u00a7a[!] \u00a7e" + onlineTarget.getName() + " \u00a7ais already online and verified with the mod! Sending mod report to Discord now...");
                plugin.getModManager().resendModsToWebhook(onlineTarget);
            } else {
                int timeout = plugin.getConfig().getInt("handshake-timeout", 10);
                sender.sendMessage("\u00a7e[!] \u00a7f" + onlineTarget.getName() + " \u00a7eis online right now. Initiating verification handshake (\u00a7f" + timeout + "s \u00a7etimeout)...");
                plugin.getModManager().triggerHandshake(onlineTarget);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (onlineTarget.isOnline() && !plugin.getModManager().isVerified(onlineTarget)) {
                            plugin.getModManager().getWebhookManager().sendVerificationFailed(onlineTarget, timeout);
                            String kickMsg = plugin.getConfig().getString("kick-message-no-mod",
                                    "\u00a7cYou are missing a required mod to join this server.");
                            onlineTarget.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                    .legacySection().deserialize(kickMsg));
                        }
                    }
                }.runTaskLater(plugin, timeout * 20L);
            }
        } else {
            sender.sendMessage("\u00a77When \u00a7f" + targetName + " \u00a77connects to the server, their mods will be verified automatically.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("fenza.modcheck")) {
            return completions;
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> subs = List.of("list", "remove", "add");
            for (String sub : subs) {
                if (sub.startsWith(prefix)) {
                    completions.add(sub);
                }
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            if (args[0].equalsIgnoreCase("remove")) {
                for (String tracked : plugin.getModManager().getTrackedPlayers()) {
                    if (tracked.startsWith(prefix)) {
                        completions.add(tracked);
                    }
                }
            } else if (args[0].equalsIgnoreCase("add")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(p.getName());
                    }
                }
            }
        }

        return completions;
    }
}
