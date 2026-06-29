package com.discordtag;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Blacklist for 'banned' users that can never complete verification regardless 
 * of minecraft acct. 
 */
public class DiscordBlacklist {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("discordtag-blacklist.json");

    public static synchronized boolean isBlacklisted(long discordUserId) {
        return load().contains(discordUserId);
    }

    public static synchronized List<Long> list() {
        return load();
    }

    /** @return false if the ID was already blacklisted */
    public static synchronized boolean add(long discordUserId) {
        List<Long> ids = load();
        if (ids.contains(discordUserId)) {
            return false;
        }
        ids.add(discordUserId);
        save(ids);
        return true;
    }

    /** @return false if the ID wasn't on the blacklist */
    public static synchronized boolean remove(long discordUserId) {
        List<Long> ids = load();
        if (!ids.remove(discordUserId)) {
            return false;
        }
        save(ids);
        return true;
    }

    private static List<Long> load() {
        if (!Files.exists(FILE)) {
            save(new ArrayList<>());
            return new ArrayList<>();
        }

        String[] raw = JsonFileIO.load(FILE, String[].class, "discordtag-blacklist.json");
        List<Long> ids = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                try {
                    ids.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
    }

    private static void save(List<Long> ids) {
        String[] raw = ids.stream().map(String::valueOf).toArray(String[]::new);
        JsonFileIO.save(FILE, raw, "discordtag-blacklist.json");
    }
}
