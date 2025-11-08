package me.MILLSBOSS.reinforcedElytra;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ReinforcedElytra extends JavaPlugin {

    @Override
    public void onEnable() {
        // Ensure config is present and loaded
        saveDefaultConfig();
        // Plugin startup logic
        Bukkit.getPluginManager().registerEvents(new AnvilDropListener(this), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
