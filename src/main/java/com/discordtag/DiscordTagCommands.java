package com.discordtag;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
                        .then(literal("enable").executes(DiscordTagCommands::enable))
                        .then(literal("disable").executes(DiscordTagCommands::disable))));
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
        ctx.getSource().sendFeedback(() -> Text.literal("Your Discord tag is now set to ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(rawName).formatted(Formatting.LIGHT_PURPLE)), false);
        return 1;
    }

    private static int enable(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordTagManager.PlayerEntry entry = DiscordTagMod.MANAGER.getEntry(player.getUuid());

        if (entry == null || entry.discordName == null) {
            ctx.getSource().sendError(Text.literal(
                    "You haven't set a Discord username yet. Try /discordtag set <username> first."));
            return 0;
        }

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
            ctx.getSource().sendFeedback(() -> Text.literal("Discord: ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(entry.discordName).formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal(entry.enabled ? "  (shown)" : "  (hidden)").formatted(Formatting.GRAY)), false);
        }
        return 1;
    }
}
