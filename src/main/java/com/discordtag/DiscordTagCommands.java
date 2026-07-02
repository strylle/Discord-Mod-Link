package com.discordtag;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class DiscordTagCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("discordtag")
                        .executes(DiscordTagCommands::showStatus)
                        .then(literal("set")
                                .then(argument("username", StringArgumentType.word())
                                        .executes(DiscordTagCommands::setName)))
                        .then(literal("verify").executes(DiscordTagCommands::verify))
                        .then(literal("unlink").executes(DiscordTagCommands::unlink))
                        .then(literal("enable").executes(DiscordTagCommands::enable))
                        .then(literal("disable").executes(DiscordTagCommands::disable))
                        .then(literal("blacklist")
                                .requires(source -> source.hasPermissionLevel(3))
                                .then(literal("add")
                                        .then(argument("discord-id-or-username", StringArgumentType.word())
                                                .executes(ctx -> resolveDiscordId(ctx, StringArgumentType.getString(ctx, "discord-id-or-username"), DiscordTagCommands::addToBlacklist))))
                                .then(literal("remove")
                                        .then(argument("discord-id-or-username", StringArgumentType.word())
                                                .executes(ctx -> resolveDiscordId(ctx, StringArgumentType.getString(ctx, "discord-id-or-username"), DiscordTagCommands::removeFromBlacklist))))
                                .then(literal("list").executes(DiscordTagCommands::blacklistList)))
                        .then(literal("whois")
                                .requires(source -> source.hasPermissionLevel(3))
                                .then(literal("player")
                                        .then(argument("player", StringArgumentType.word())
                                                .executes(DiscordTagCommands::whoisPlayer)))
                                .then(literal("discord")
                                        .then(argument("discord-id-or-username", StringArgumentType.word())
                                                .executes(ctx -> resolveDiscordId(ctx, StringArgumentType.getString(ctx, "discord-id-or-username"), DiscordTagCommands::whoisDiscord)))))));
    }

    private static int setName(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        String rawName = StringArgumentType.getString(ctx, "username").toLowerCase();

        if (!DiscordTagManager.isValidDiscordUsername(rawName)) {
            ctx.getSource().sendError(Text.literal(
                    "That doesn't look like a Discord username (2-32 chars: lowercase letters, numbers, '.' or '_')."));
            return 0;
        }

        DiscordTagMod.MANAGER.setDiscordName(player, rawName);

        DiscordBotManager bot = DiscordTagMod.runningBot();
        if (bot != null) {
            ctx.getSource().sendFeedback(() -> Text.literal("Your Discord tag is now set to ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(rawName).formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal(" - sending a verification request now...").formatted(Formatting.GRAY)), false);
            bot.requestVerification(player.getUuid(), player.getGameProfile().getName(), rawName);
        } else {
            ctx.getSource().sendFeedback(() -> Text.literal("Your Discord tag is now set to ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(rawName).formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal(" (verification unavailable - ask an admin to configure the Discord bot)").formatted(Formatting.GRAY)), false);
        }
        return 1;
    }

    private static int verify(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordTagManager.PlayerEntry entry = getEntryIfNameSet(ctx, player);
        if (entry == null) return 0;

        if (entry.verified) {
            ctx.getSource().sendFeedback(() -> Text.literal("You're already verified as ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(entry.discordUsername).formatted(Formatting.GREEN)), false);
            return 1;
        }

        DiscordBotManager bot = DiscordTagMod.runningBot();
        if (bot == null) {
            ctx.getSource().sendError(Text.literal(
                    "Verification is currently unavailable - ask an admin to configure the Discord bot."));
            return 0;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Sending a verification request to Discord...").formatted(Formatting.GRAY), false);
        bot.requestVerification(player.getUuid(), player.getGameProfile().getName(), entry.discordName);
        return 1;
    }

    private static int unlink(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordTagManager.PlayerEntry entry = DiscordTagMod.MANAGER.getEntry(player.getUuid());

        if (entry == null || !entry.verified) {
            ctx.getSource().sendError(Text.literal("You're not verified, so there's nothing to unlink."));
            return 0;
        }

        long discordUserId = entry.discordUserId;
        String ign = player.getGameProfile().getName();

        DiscordTagMod.MANAGER.unlink(player.getUuid(), player);
        ctx.getSource().sendFeedback(() -> Text.literal(
                "Unlinked your Discord account. Run /discordtag set <username> to verify again.")
                .formatted(Formatting.GRAY), false);

        DiscordBotManager bot = DiscordTagMod.runningBot();
        if (bot != null) {
            bot.notifyUnlinkedFromGame(discordUserId, ign);
        }
        return 1;
    }

    private interface DiscordIdCallback {
        void apply(ServerCommandSource source, long discordUserId);
    }

    // can be a raw int Discord ID (resolved immediately) or a Discord username
    private static int resolveDiscordId(CommandContext<ServerCommandSource> ctx, String target, DiscordIdCallback action) {
        Long discordUserId = tryParseDiscordId(target);
        if (discordUserId != null) {
            action.apply(ctx.getSource(), discordUserId);
            return 1;
        }
        return findDiscordUserThenApply(ctx, target, action);
    }

    private static int findDiscordUserThenApply(CommandContext<ServerCommandSource> ctx, String discordUsername, DiscordIdCallback action) {
        ServerCommandSource source = ctx.getSource();
        DiscordBotManager bot = DiscordTagMod.runningBot();
        if (bot == null) {
            source.sendError(Text.literal("Can't look up Discord usernames right now - the bot isn't connected."));
            return 0;
        }

        bot.resolveDiscordIdByUsername(discordUsername, discordUserId -> source.getServer().execute(() -> {
            if (discordUserId == null) {
                source.sendError(Text.literal("Couldn't find a Discord user named '" + discordUsername + "' in the server."));
            } else {
                action.apply(source, discordUserId);
            }
        }));
        source.sendFeedback(() -> Text.literal("Looking up Discord user '" + discordUsername + "'...").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int blacklistList(CommandContext<ServerCommandSource> ctx) {
        List<Long> ids = DiscordBlacklist.list();
        if (ids.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("The verification blacklist is empty."), false);
        } else {
            String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
            ctx.getSource().sendFeedback(() -> Text.literal("Blacklisted Discord IDs: " + joined), false);
        }
        return 1;
    }

    private static Long tryParseDiscordId(String target) {
        try {
            return Long.parseLong(target.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addToBlacklist(ServerCommandSource source, long discordUserId) {
        if (!DiscordBlacklist.add(discordUserId)) {
            source.sendFeedback(() -> Text.literal("That Discord account is already blacklisted.").formatted(Formatting.GRAY), false);
            return;
        }

        // revoke (not unlink!) 
        UUID linkedUuid = DiscordTagMod.MANAGER.findByDiscordId(discordUserId);
        if (linkedUuid != null) {
            ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(linkedUuid);
            DiscordTagMod.MANAGER.revokeVerification(linkedUuid, online);
            if (online != null) {
                online.sendMessage(Text.literal("Your Discord verification was revoked by a moderator.").formatted(Formatting.RED), false);
            }
        }

        source.sendFeedback(() -> Text.literal("Blacklisted Discord user " + discordUserId + " from verifying.").formatted(Formatting.RED), true);
    }

    private static void removeFromBlacklist(ServerCommandSource source, long discordUserId) {
        if (!DiscordBlacklist.remove(discordUserId)) {
            source.sendFeedback(() -> Text.literal("That Discord account wasn't blacklisted.").formatted(Formatting.GRAY), false);
            return;
        }
        source.sendFeedback(() -> Text.literal("Removed Discord user " + discordUserId + " from the blacklist.").formatted(Formatting.GREEN), true);
    }

    private static int whoisPlayer(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();

        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        UUID uuid;
        String resolvedName;
        if (online != null) {
            uuid = online.getUuid();
            resolvedName = online.getGameProfile().getName();
        } else {
            GameProfile profile = server.getUserCache() != null
                    ? server.getUserCache().findByName(name).orElse(null)
                    : null;
            if (profile == null) {
                source.sendError(Text.literal("No known player named '" + name + "'."));
                return 0;
            }
            uuid = profile.getId();
            resolvedName = profile.getName();
        }

        printWhois(source, uuid, resolvedName);
        return 1;
    }

    private static void whoisDiscord(ServerCommandSource source, long discordUserId) {
        boolean blacklisted = DiscordBlacklist.isBlacklisted(discordUserId);
        UUID uuid = DiscordTagMod.MANAGER.findByDiscordId(discordUserId);
        if (uuid == null) {
            MutableText message = Text.literal("No Minecraft account is linked to Discord ID " + discordUserId + ".").formatted(Formatting.GRAY);
            if (blacklisted) {
                message.append(Text.literal("  BLACKLISTED").formatted(Formatting.RED, Formatting.BOLD));
            }
            source.sendFeedback(() -> message, false);
            return;
        }

        printWhois(source, uuid, resolvePlayerName(source.getServer(), uuid));
    }

    private static String resolvePlayerName(MinecraftServer server, UUID uuid) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (server.getUserCache() != null) {
            return server.getUserCache().getByUuid(uuid).map(GameProfile::getName).orElse(uuid.toString());
        }
        return uuid.toString();
    }

    private static void printWhois(ServerCommandSource source, UUID uuid, String name) {
        DiscordTagManager.PlayerEntry entry = DiscordTagMod.MANAGER.getEntry(uuid);
        if (entry == null || entry.discordName == null) {
            source.sendFeedback(() -> Text.literal(name + " (" + uuid + "): no Discord data on file.").formatted(Formatting.GRAY), false);
            return;
        }

        boolean blacklisted = entry.discordUserId != null && DiscordBlacklist.isBlacklisted(entry.discordUserId);

        MutableText message = Text.literal(name + " (" + uuid + "):").formatted(Formatting.GRAY)
                .append(Text.literal("\n  Claimed Discord tag: ").formatted(Formatting.GRAY))
                .append(Text.literal(entry.discordName).formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal("\n  Verified: ").formatted(Formatting.GRAY))
                .append(Text.literal(entry.verified ? "yes" : "no").formatted(entry.verified ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("\n  Tag visibility: ").formatted(Formatting.GRAY))
                .append(Text.literal(entry.enabled ? "shown" : "hidden").formatted(Formatting.GRAY));

        if (entry.verified) {
            message.append(Text.literal("\n  Discord account: ").formatted(Formatting.GRAY))
                    .append(Text.literal(entry.discordUsername + " (" + entry.discordUserId + ")").formatted(Formatting.AQUA))
                    .append(Text.literal("\n  Verified at: ").formatted(Formatting.GRAY))
                    .append(Text.literal(new Date(entry.verifiedAt).toString()).formatted(Formatting.GRAY));
        }

        if (blacklisted) {
            message.append(Text.literal("\n  BLACKLISTED").formatted(Formatting.RED, Formatting.BOLD));
        }

        source.sendFeedback(() -> message, false);
    }

    private static DiscordTagManager.PlayerEntry getEntryIfNameSet(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
        DiscordTagManager.PlayerEntry entry = DiscordTagMod.MANAGER.getEntry(player.getUuid());
        if (entry == null || entry.discordName == null) {
            ctx.getSource().sendError(Text.literal(
                    "You haven't set a Discord username yet. Try /discordtag set <username> first."));
            return null;
        }
        return entry;
    }

    private static int enable(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (getEntryIfNameSet(ctx, player) == null) return 0;

        DiscordTagMod.MANAGER.setEnabled(player, true);
        ctx.getSource().sendFeedback(() -> Text.literal("Your Discord tag is now visible.")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int disable(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordTagMod.MANAGER.setEnabled(player, false);
        ctx.getSource().sendFeedback(() -> Text.literal("Your Discord tag is now hidden.")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int showStatus(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordTagManager.PlayerEntry entry = DiscordTagMod.MANAGER.getEntry(player.getUuid());

        if (entry == null || entry.discordName == null) {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "No Discord username set yet. Use /discordtag set <username>."), false);
        } else {
            Formatting color = entry.verified ? Formatting.GREEN : Formatting.LIGHT_PURPLE;
            ctx.getSource().sendFeedback(() -> Text.literal("Discord: ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(entry.displayName()).formatted(color))
                    .append(Text.literal(entry.verified ? "  (verified)" : "  (unverified)").formatted(Formatting.GRAY))
                    .append(Text.literal(entry.enabled ? "  (shown)" : "  (hidden)").formatted(Formatting.GRAY)), false);
        }
        return 1;
    }
}
