package com.discordtag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Why a scoreboard team? cause i dont know how to chqnge renderer code and thats a whole nother can of worms lol
 */
public class DiscordTagManager {

    private static final Pattern DISCORD_USERNAME = Pattern.compile("^[a-z0-9_](?:[a-z0-9_.]{0,30}[a-z0-9_])?$");

    private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("discordtag.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, PlayerEntry> entries = new HashMap<>();

    public static class PlayerEntry {
        public String discordName;
        public boolean enabled = true;
    }

    public static boolean isValidDiscordUsername(String name) {
        return !name.contains("..") && DISCORD_USERNAME.matcher(name).matches();
    }

    public synchronized void load() {
        if (!Files.exists(DATA_FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8)) {
            Map<String, PlayerEntry> raw = GSON.fromJson(reader, new TypeToken<Map<String, PlayerEntry>>() {}.getType());
            entries.clear();
            if (raw != null) {
                raw.forEach((key, value) -> entries.put(UUID.fromString(key), value));
            }
        } catch (IOException e) {
            DiscordTagMod.LOGGER.error("[DiscordTag] Failed to load discordtag.json", e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            Map<String, PlayerEntry> raw = new HashMap<>();
            entries.forEach((key, value) -> raw.put(key.toString(), value));
            try (Writer writer = Files.newBufferedWriter(DATA_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(raw, writer);
            }
        } catch (IOException e) {
            DiscordTagMod.LOGGER.error("[DiscordTag] Failed to save discordtag.json", e);
        }
    }

    public synchronized PlayerEntry getEntry(UUID uuid) {
        return entries.get(uuid);
    }

    public synchronized void setDiscordName(ServerPlayerEntity player, String discordName) {
        PlayerEntry entry = entries.computeIfAbsent(player.getUuid(), id -> new PlayerEntry());
        entry.discordName = discordName;
        entry.enabled = true;
        save();
        applyTag(player, discordName);
    }

    public synchronized void setEnabled(ServerPlayerEntity player, boolean enabled) {
        PlayerEntry entry = entries.computeIfAbsent(player.getUuid(), id -> new PlayerEntry());
        entry.enabled = enabled;
        save();
        if (enabled && entry.discordName != null) {
            applyTag(player, entry.discordName);
        } else {
            clearTag(player);
        }
    }

    public void applyTag(ServerPlayerEntity player, String discordName) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        Scoreboard scoreboard = server.getScoreboard();
        String teamName = teamNameFor(player.getUuid());

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
        }

        team.setSuffix(Text.literal(" (" + discordName + ")").formatted(Formatting.LIGHT_PURPLE));

        scoreboard.addScoreHolderToTeam(player.getGameProfile().getName(), team);
    }

    public void clearTag(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.getScoreboard().clearTeam(player.getGameProfile().getName());
    }

    private static String teamNameFor(UUID uuid) {
        return "dt_" + uuid.toString().replace("-", "").substring(0, 10);
    }
}
