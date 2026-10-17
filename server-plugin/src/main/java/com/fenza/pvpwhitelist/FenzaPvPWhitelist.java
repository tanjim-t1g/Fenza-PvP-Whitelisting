package com.fenza.pvpwhitelist;

import com.fenza.pvpwhitelist.command.ModCheckCommand;
import com.fenza.pvpwhitelist.command.ModListCommand;
import com.fenza.pvpwhitelist.listener.PlayerConnectionListener;
import com.fenza.pvpwhitelist.manager.PlayerModManager;
import org.bukkit.plugin.java.JavaPlugin;

public class FenzaPvPWhitelist extends JavaPlugin {

    public static final String CHANNEL = "fenza:sync";

    private PlayerModManager modManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        modManager = new PlayerModManager(this);

        PlayerConnectionListener connectionListener = new PlayerConnectionListener(this);

        // Register plugin messaging channels
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, connectionListener);

        // Register event listeners
        getServer().getPluginManager().registerEvents(connectionListener, this);

        // Register commands
        ModListCommand modListCommand = new ModListCommand(this);
        getCommand("fenzamods").setExecutor(modListCommand);
        getCommand("fenzamods").setTabCompleter(modListCommand);

        ModCheckCommand modCheckCommand = new ModCheckCommand(this);
        getCommand("modcheck").setExecutor(modCheckCommand);
        getCommand("modcheck").setTabCompleter(modCheckCommand);

        getLogger().info("Fenza-PvP-Whitelisting has been enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getLogger().info("Fenza-PvP-Whitelisting has been disabled.");
    }

    public PlayerModManager getModManager() {
        return modManager;
    }
}
