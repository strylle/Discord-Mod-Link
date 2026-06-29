package com.discordtag;

import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class DiscordBotConfig {

    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("discordtag-bot.json");

    public enum Enforcement { LOCK, NAG }

    public String token = "";
    public String guildId = "";
    public String enforcement = "lock";

    // presence: "online", "idle", "dnd", or "invisible"
    public String presence = "online";
    public String activityType = "watching";
    public String activityText = "for /discordtag verify";

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public Enforcement enforcementMode() {
        return "nag".equalsIgnoreCase(enforcement) ? Enforcement.NAG : Enforcement.LOCK;
    }

    public OnlineStatus onlineStatus() {
        if (presence == null) return OnlineStatus.ONLINE;
        switch (presence.toLowerCase()) {
            case "idle": return OnlineStatus.IDLE;
            case "dnd":
            case "do_not_disturb": return OnlineStatus.DO_NOT_DISTURB;
            case "invisible": return OnlineStatus.INVISIBLE;
            default: return OnlineStatus.ONLINE;
        }
    }

    public Activity activity() {
        if (activityText == null || activityText.isBlank()) {
            return null;
        }
        String type = activityType == null ? "playing" : activityType.toLowerCase();
        switch (type) {
            case "watching": return Activity.watching(activityText);
            case "listening": return Activity.listening(activityText);
            case "competing": return Activity.competing(activityText);
            default: return Activity.playing(activityText);
        }
    }

    public static DiscordBotConfig load() {
        if (!Files.exists(CONFIG_FILE)) {
            DiscordBotConfig template = new DiscordBotConfig();
            JsonFileIO.save(CONFIG_FILE, template, "discordtag-bot.json template");
            DiscordTagMod.LOGGER.warn("[DiscordTag] Created {} - fill in your bot token and guild ID, then restart the server to enable Discord verification.", CONFIG_FILE);
            return template;
        }

        DiscordBotConfig config = JsonFileIO.load(CONFIG_FILE, DiscordBotConfig.class, "discordtag-bot.json");
        if (config == null) {
            config = new DiscordBotConfig();
        }
        DiscordTagMod.LOGGER.info("[DiscordTag] Loaded bot config (token present: {})", config.hasToken());
        return config;
    }
}
