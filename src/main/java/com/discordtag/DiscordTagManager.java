package com.discordtag;

import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

    private final Map<UUID, PlayerEntry> entries = new HashMap<>();

    public static class PlayerEntry {
        public String discordName;
        public boolean enabled = true;

        public boolean verified = false;
        public Long discordUserId;
        public String discordUsername;
        public long verifiedAt;

        public String displayName() {
            return verified ? discordUsername : discordName;
        }
    }

    public static boolean isValidDiscordUsername(String name) {
        return !name.contains("..") && DISCORD_USERNAME.matcher(name).matches();
    }

    public synchronized void load() {
        Map<String, PlayerEntry> raw = JsonFileIO.load(
                DATA_FILE, new TypeToken<Map<String, PlayerEntry>>() {}.getType(), "discordtag.json");
        entries.clear();
        if (raw != null) {
            raw.forEach((key, value) -> entries.put(UUID.fromString(key), value));
        }
    }

    public synchronized void save() {
        Map<String, PlayerEntry> raw = new HashMap<>();
        entries.forEach((key, value) -> raw.put(key.toString(), value));
        JsonFileIO.save(DATA_FILE, raw, "discordtag.json");
    }

    public synchronized PlayerEntry getEntry(UUID uuid) {
        return entries.get(uuid);
    }

    public synchronized void setDiscordName(ServerPlayerEntity player, String discordName) {
        PlayerEntry entry = entries.computeIfAbsent(player.getUuid(), id -> new PlayerEntry());
        entry.discordName = discordName;
        entry.enabled = true;

        entry.verified = false;
        entry.discordUserId = null;
        entry.discordUsername = null;
        entry.verifiedAt = 0L;
        save();
        applyTag(player, discordName, false);
    }

    public synchronized void setEnabled(ServerPlayerEntity player, boolean enabled) {
        PlayerEntry entry = entries.computeIfAbsent(player.getUuid(), id -> new PlayerEntry());
        entry.enabled = enabled;
        save();
        if (enabled && entry.discordName != null) {
            applyTag(player, entry.displayName(), entry.verified);
        } else {
            clearTag(player);
        }
    }

    public synchronized void markVerified(UUID uuid, long discordUserId, String discordUsername,
                                           ServerPlayerEntity onlinePlayerOrNull) {
        PlayerEntry entry = entries.computeIfAbsent(uuid, id -> new PlayerEntry());
        entry.verified = true;
        entry.discordUserId = discordUserId;
        entry.discordUsername = discordUsername;
        entry.verifiedAt = System.currentTimeMillis();
        save();
        if (onlinePlayerOrNull != null) {
            applyTag(onlinePlayerOrNull, discordUsername, true);
        }
    }

    public synchronized UUID findByDiscordId(long discordUserId) {
        for (Map.Entry<UUID, PlayerEntry> e : entries.entrySet()) {
            if (e.getValue().discordUserId != null && e.getValue().discordUserId == discordUserId) {
                return e.getKey();
            }
        }
        return null;
    }

    public synchronized UUID findVerifiedByDiscordId(long discordUserId) {
        for (Map.Entry<UUID, PlayerEntry> e : entries.entrySet()) {
            PlayerEntry entry = e.getValue();
            if (entry.verified && entry.discordUserId != null && entry.discordUserId == discordUserId) {
                return e.getKey();
            }
        }
        return null;
    }

    public synchronized void revokeVerification(UUID uuid, ServerPlayerEntity onlinePlayerOrNull) {
        PlayerEntry entry = entries.get(uuid);
        if (entry == null) return;
        entry.verified = false;
        save();
        showAsUnverified(entry, onlinePlayerOrNull);
    }

    public synchronized void unlink(UUID uuid, ServerPlayerEntity onlinePlayerOrNull) {
        PlayerEntry entry = entries.get(uuid);
        if (entry == null) return;
        entry.verified = false;
        entry.discordUserId = null;
        entry.discordUsername = null;
        entry.verifiedAt = 0L;
        save();
        showAsUnverified(entry, onlinePlayerOrNull);
    }

    private void showAsUnverified(PlayerEntry entry, ServerPlayerEntity onlinePlayerOrNull) {
        if (onlinePlayerOrNull == null) return;
        if (entry.enabled && entry.discordName != null) {
            applyTag(onlinePlayerOrNull, entry.discordName, false);
        } else {
            clearTag(onlinePlayerOrNull);
        }
    }

    public void applyTag(ServerPlayerEntity player, String displayName, boolean verified) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        Scoreboard scoreboard = server.getScoreboard();
        String teamName = teamNameFor(player.getUuid());

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
        }

        Formatting color = verified ? Formatting.GREEN : Formatting.RED;
        team.setSuffix(Text.literal(" (" + displayName + ")").formatted(color));

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
