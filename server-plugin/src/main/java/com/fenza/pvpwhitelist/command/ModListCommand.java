package com.fenza.pvpwhitelist.command;

import com.fenza.pvpwhitelist.FenzaPvPWhitelist;
import com.fenza.pvpwhitelist.manager.PlayerModManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModListCommand implements CommandExecutor, TabCompleter {

    private final FenzaPvPWhitelist plugin;

    public ModListCommand(FenzaPvPWhitelist plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission("fenza.modlist")) {
            sender.sendMessage("\u00a7cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("\u00a7cUsage: /fenzamods <player> [--all]");
            return true;
        }

        String targetName = args[0];
        boolean showAll = args.length >= 2 && args[1].equalsIgnoreCase("--all");

        Player onlineTarget = Bukkit.getPlayer(targetName);
        List<String> rawMods;
        String displayName;
        String status;

        if (onlineTarget != null) {
            displayName = onlineTarget.getName();
            rawMods = plugin.getModManager().getPlayerMods(onlineTarget);
            status = "\u00a7a[ONLINE]";
        } else {
            displayName = targetName;
            rawMods = plugin.getModManager().getOfflinePlayerMods(targetName);
            status = "\u00a77[OFFLINE - Saved File]";
        }

        if (rawMods.isEmpty()) {
            sender.sendMessage("\u00a7eNo mod data available for " + displayName + ".");
            sender.sendMessage("\u00a77(Check plugins/Fenza-PvP-Whitelisting/player-mods/)");
            return true;
        }

        List<String> userMods = new ArrayList<>();
        List<String> libraryMods = new ArrayList<>();

        for (String mod : rawMods) {
            if (PlayerModManager.isLibraryMod(mod)) {
                libraryMods.add(mod);
            } else {
                userMods.add(mod);
            }
        }

        List<String> displayList = showAll ? rawMods : userMods;

        sender.sendMessage("\u00a76=== Mods for \u00a7e" + displayName + " " + status + " \u00a76===");
        for (String mod : displayList) {
            sender.sendMessage("\u00a77 - \u00a7f" + mod);
        }

        if (!showAll && !libraryMods.isEmpty()) {
            sender.sendMessage("\u00a78[+ " + libraryMods.size() + " bundled libraries hidden. Type '/fenzamods " + displayName + " --all' to view everything]");
        }
        sender.sendMessage("\u00a76Total shown: \u00a7e" + displayList.size() + " \u00a77(Total in client: " + rawMods.size() + ")\u00a7r");

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("fenza.modlist")) {
            return completions;
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            // Online players
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(player.getName());
                }
            }
            // Offline saved files
            File folder = plugin.getModManager().getDataFolder();
            if (folder != null && folder.exists()) {
                File[] files = folder.listFiles((dir, name) -> name.endsWith(".txt"));
                if (files != null) {
                    for (File file : files) {
                        String nameWithoutExt = file.getName().replace(".txt", "");
                        if (nameWithoutExt.startsWith(prefix) && !completions.contains(nameWithoutExt)) {
                            completions.add(nameWithoutExt);
                        }
                    }
                }
            }
        } else if (args.length == 2) {
            if ("--all".startsWith(args[1].toLowerCase())) {
                completions.add("--all");
            }
        }
        return completions;
    }
}
