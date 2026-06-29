package com.discordtag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** shared JSON file read/write because i suck at organization */
final class JsonFileIO {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFileIO() {
    }

    static <T> T load(Path path, Type type, String errorContext) {
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (IOException e) {
            DiscordTagMod.LOGGER.error("[DiscordTag] Failed to read " + errorContext, e);
            return null;
        }
    }

    static void save(Path path, Object value, String errorContext) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
        } catch (IOException e) {
            DiscordTagMod.LOGGER.error("[DiscordTag] Failed to write " + errorContext, e);
        }
    }
}
