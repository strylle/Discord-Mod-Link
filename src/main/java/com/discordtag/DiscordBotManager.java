package com.discordtag;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Embedded Discord bot (runs in the same JVM as the Fabric server)
 */
public class DiscordBotManager {

    // rate limit because i realized u could js ddos the server spamming it
    private static final long VERIFICATION_REQUEST_COOLDOWN_MS = 60_000;

    private final DiscordTagManager manager;
    private final DiscordBotConfig config;
    private final Map<Long, Long> lastVerificationRequestAt = new ConcurrentHashMap<>();
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordTag-Bot-Init");
        t.setDaemon(true);
        return t;
    });

    private volatile JDA jda;
    private MinecraftServer server;
    private long guildId;

    public DiscordBotManager(DiscordTagManager manager, DiscordBotConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void start(MinecraftServer server) {
        this.server = server;
        try {
            this.guildId = Long.parseLong(config.guildId.trim());
        } catch (NumberFormatException e) {
            DiscordTagMod.LOGGER.error("[DiscordTag] discordtag-bot.json guildId is not a valid Discord guild ID - bot not started.");
            return;
        }

        initExecutor.submit(() -> {
            try {
                JDABuilder builder = JDABuilder.createDefault(config.token)
                        .addEventListeners(new InteractionListener())
                        .setStatus(config.onlineStatus());
                Activity activity = config.activity();
                if (activity != null) {
                    builder.setActivity(activity);
                }
                JDA built = builder.build();
                built.awaitReady();
                this.jda = built;

                Guild guild = built.getGuildById(guildId);
                if (guild == null) {
                    DiscordTagMod.LOGGER.error("[DiscordTag] Bot is not a member of configured guild {} - check discordtag-bot.json.", guildId);
                    return;
                }
                guild.upsertCommand("unlink", "Unlink your Minecraft account from this Discord account.").queue();

                SlashCommandData blacklistCommand = Commands.slash("blacklist", "Manage who is blocked from verifying their Minecraft account.")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS))
                        .addSubcommands(
                                new SubcommandData("add", "Block a Discord user from verifying, and revoke their verification if they have one.")
                                        .addOption(OptionType.USER, "user", "The Discord user to blacklist", true),
                                new SubcommandData("remove", "Allow a previously blacklisted Discord user to verify again.")
                                        .addOption(OptionType.USER, "user", "The Discord user to remove from the blacklist", true),
                                new SubcommandData("list", "List blacklisted Discord user IDs."));
                guild.upsertCommand(blacklistCommand).queue();

                DiscordTagMod.LOGGER.info("[DiscordTag] Discord bot connected to guild {}.", guild.getName());
            } catch (Exception e) {
                DiscordTagMod.LOGGER.error("[DiscordTag] Failed to start Discord bot", e);
            }
        });
    }

    public void stop() {
        JDA current = jda;
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitShutdown(10, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        initExecutor.shutdownNow();
    }

    public boolean isRunning() {
        JDA current = jda;
        return current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    private void findGuildMemberNamed(Guild guild, String username, Consumer<Member> onFound, Runnable onNotFound, Runnable onError) {
        guild.retrieveMembersByPrefix(username, 5)
                .onSuccess(members -> {
                    Member match = members.stream()
                            .filter(m -> m.getUser().getName().equalsIgnoreCase(username))
                            .findFirst()
                            .orElse(null);
                    if (match != null) {
                        onFound.accept(match);
                    } else {
                        onNotFound.run();
                    }
                })
                .onError(error -> {
                    DiscordTagMod.LOGGER.error("[DiscordTag] Discord member lookup failed", error);
                    onError.run();
                });
    }

    public void resolveDiscordIdByUsername(String discordUsername, Consumer<Long> onResult) {
        JDA current = jda;
        Guild guild = current != null ? current.getGuildById(guildId) : null;
        if (guild == null) {
            onResult.accept(null);
            return;
        }

        Runnable notFound = () -> onResult.accept(null);
        findGuildMemberNamed(guild, discordUsername, match -> onResult.accept(match.getIdLong()), notFound, notFound);
    }

    public void requestVerification(UUID uuid, String ign, String claimedDiscordName) {
        JDA current = jda;
        if (current == null) return;
        Guild guild = current.getGuildById(guildId);
        if (guild == null) return;

        findGuildMemberNamed(guild, claimedDiscordName,
                match -> {
                    if (DiscordBlacklist.isBlacklisted(match.getIdLong())) {
                        DiscordTagMod.LOGGER.warn("[DiscordTag] Blocked verification attempt for blacklisted Discord user {} (claimed by {})", match.getIdLong(), ign);
                        notifyPlayer(uuid, Text.literal("Verification is not available for that Discord account.").formatted(Formatting.RED));
                        return;
                    }
                    UUID alreadyLinked = manager.findVerifiedByDiscordId(match.getIdLong());
                    if (alreadyLinked != null && !alreadyLinked.equals(uuid)) {
                        notifyPlayer(uuid, Text.literal(
                                "That Discord account is already verified on a different Minecraft account. Ask them to run /discordtag unlink first.")
                                .formatted(Formatting.RED));
                        return;
                    }
                    if (!tryReserveVerificationRequest(match.getIdLong())) {
                        notifyPlayer(uuid, Text.literal(
                                "A verification request was already sent to that Discord account recently - ask them to check their DMs, or try again in a minute.")
                                .formatted(Formatting.RED));
                        return;
                    }
                    sendConfirmation(uuid, ign, match);
                },
                () -> notifyPlayer(uuid, Text.literal(
                        "Couldn't find that Discord username in the server - check spelling, or make sure you've joined the Discord server.")
                        .formatted(Formatting.RED)),
                () -> notifyPlayer(uuid, Text.literal("Discord verification lookup failed - try again later.").formatted(Formatting.RED)));
    }

    private boolean tryReserveVerificationRequest(long discordUserId) {
        long now = System.currentTimeMillis();
        boolean[] allowed = new boolean[1];
        lastVerificationRequestAt.compute(discordUserId, (id, last) -> {
            if (last != null && now - last < VERIFICATION_REQUEST_COOLDOWN_MS) {
                allowed[0] = false;
                return last;
            }
            allowed[0] = true;
            return now;
        });
        return allowed[0];
    }

    private void sendConfirmation(UUID uuid, String ign, Member member) {
        long discordUserId = member.getIdLong();
        String confirmId = "dt:confirm:" + uuid + ":" + discordUserId;
        String denyId = "dt:deny:" + uuid + ":" + discordUserId;

        member.getUser().openPrivateChannel().queue(channel -> {
            channel.sendMessage("A Minecraft player claiming to be you wants to link their account: **" + ign + "**. Is this you?")
                    .setActionRow(Button.success(confirmId, "Confirm"), Button.danger(denyId, "Deny"))
                    .queue(
                            msg -> notifyPlayer(uuid, Text.literal("Sent a confirmation request to your Discord account - check your DMs.").formatted(Formatting.GRAY)),
                            error -> notifyPlayer(uuid, dmFailedMessage())
                    );
        }, error -> notifyPlayer(uuid, dmFailedMessage()));
    }

    public void notifyUnlinkedFromGame(long discordUserId, String ign) {
        JDA current = jda;
        if (current == null) return;

        current.retrieveUserById(discordUserId).queue(user ->
                user.openPrivateChannel().queue(channel ->
                        channel.sendMessage("Your Minecraft account (**" + ign + "**) was unlinked from Discord verification via an in-game command. "
                                        + "Run /discordtag set <username> in-game if you want to verify again.")
                                .queue(null, error -> {}),
                        error -> {}),
                error -> DiscordTagMod.LOGGER.warn("[DiscordTag] Couldn't notify Discord user {} of in-game unlink", discordUserId));
    }

    private static Text dmFailedMessage() {
        return Text.literal("Couldn't DM you on Discord - check that you allow DMs from server members.").formatted(Formatting.RED);
    }

    private void whenPlayerIsOnline(UUID uuid, Consumer<ServerPlayerEntity> action) {
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid)));
    }

    private void notifyPlayer(UUID uuid, Text message) {
        whenPlayerIsOnline(uuid, player -> {
            if (player != null) {
                player.sendMessage(message, false);
            }
        });
    }

    private class InteractionListener extends ListenerAdapter {

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String[] parts = event.getComponentId().split(":");
            if (parts.length != 4 || !"dt".equals(parts[0])) {
                return;
            }

            String action = parts[1];
            UUID uuid = UUID.fromString(parts[2]);
            long expectedDiscordId = Long.parseLong(parts[3]);

            if (event.getUser().getIdLong() != expectedDiscordId) {
                event.reply("This confirmation isn't for you.").setEphemeral(true).queue();
                return;
            }

            if ("confirm".equals(action)) {
                if (DiscordBlacklist.isBlacklisted(expectedDiscordId)) {
                    event.editMessage("This account can't be linked.").setComponents(List.of()).queue();
                    DiscordTagMod.LOGGER.warn("[DiscordTag] Blocked verification confirm for blacklisted Discord user {}", expectedDiscordId);
                    notifyPlayer(uuid, Text.literal("Verification is not available for that Discord account.").formatted(Formatting.RED));
                    return;
                }

                JDA current = jda;
                Guild guild = current != null ? current.getGuildById(guildId) : null;
                if (guild == null) {
                    event.editMessage("Verification is currently unavailable - try again later.").setComponents(List.of()).queue();
                    return;
                }

                UserSnowflake target = UserSnowflake.fromId(expectedDiscordId);
                Runnable checkMembership = () -> guild.retrieveMember(target).queue(
                        member -> finishLink(event, uuid, expectedDiscordId),
                        memberError -> denyNotInGuild(event, uuid, expectedDiscordId, "no longer a member of")
                );
                try {
                    guild.retrieveBan(target).queue(
                            ban -> denyNotInGuild(event, uuid, expectedDiscordId, "banned from"),
                            banError -> checkMembership.run());
                } catch (InsufficientPermissionException e) {
                    checkMembership.run();
                }
            } else if ("deny".equals(action)) {
                event.editMessage("Denied.").setComponents(List.of()).queue();
                notifyPlayer(uuid, Text.literal(
                        "Your Discord verification request was denied. Make sure you typed the right username and try /discordtag verify again.")
                        .formatted(Formatting.RED));
            }
        }

        private void denyNotInGuild(ButtonInteractionEvent event, UUID uuid, long discordUserId, String reason) {
            event.editMessage("This account can't be linked.").setComponents(List.of()).queue();
            DiscordTagMod.LOGGER.warn("[DiscordTag] Blocked verification confirm - Discord user {} is {} the server", discordUserId, reason);
            notifyPlayer(uuid, Text.literal(
                    "Couldn't verify - make sure you're a member of the Discord server.")
                    .formatted(Formatting.RED));
        }

        private void finishLink(ButtonInteractionEvent event, UUID uuid, long discordUserId) {
            UUID alreadyLinked = manager.findVerifiedByDiscordId(discordUserId);
            if (alreadyLinked != null && !alreadyLinked.equals(uuid)) {
                event.editMessage("This Discord account is already linked to a different Minecraft account.").setComponents(List.of()).queue();
                notifyPlayer(uuid, Text.literal(
                        "That Discord account is already verified on a different Minecraft account. Ask them to run /discordtag unlink first.")
                        .formatted(Formatting.RED));
                return;
            }
            event.editMessage("Linked ✅ - you can close this.").setComponents(List.of()).queue();
            whenPlayerIsOnline(uuid, player -> {
                manager.markVerified(uuid, discordUserId, event.getUser().getName(), player);
                if (player != null) {
                    player.sendMessage(Text.literal("Your Discord account is now verified!").formatted(Formatting.GREEN), false);
                }
            });
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            if ("unlink".equals(event.getName())) {
                handleUnlink(event);
            } else if ("blacklist".equals(event.getName())) {
                handleBlacklist(event);
            }
        }

        private void handleUnlink(SlashCommandInteractionEvent event) {
            UUID uuid = manager.findByDiscordId(event.getUser().getIdLong());
            if (uuid == null) {
                event.reply("You're not linked to any Minecraft account.").setEphemeral(true).queue();
                return;
            }

            event.reply("Unlinked from your Minecraft account.").setEphemeral(true).queue();
            whenPlayerIsOnline(uuid, player -> {
                manager.unlink(uuid, player);
                if (player != null) {
                    player.sendMessage(Text.literal(
                            "Your Discord account was unlinked (via Discord). Run /discordtag set <username> to verify again.")
                            .formatted(Formatting.RED), false);
                }
            });
        }

        private void handleBlacklist(SlashCommandInteractionEvent event) {
            String subcommand = event.getSubcommandName();
            if ("list".equals(subcommand)) {
                List<Long> ids = DiscordBlacklist.list();
                String body = ids.isEmpty() ? "The blacklist is empty." : ids.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
                event.reply(ids.isEmpty() ? body : "Blacklisted Discord IDs: " + body).setEphemeral(true).queue();
                return;
            }

            OptionMapping userOption = event.getOption("user");
            if (userOption == null) {
                event.reply("Missing user.").setEphemeral(true).queue();
                return;
            }
            User target = userOption.getAsUser();
            long discordUserId = target.getIdLong();

            if ("add".equals(subcommand)) {
                if (!DiscordBlacklist.add(discordUserId)) {
                    event.reply(target.getName() + " is already blacklisted.").setEphemeral(true).queue();
                    return;
                }
                event.reply("Blacklisted " + target.getName() + " from verifying.").setEphemeral(true).queue();

                UUID linkedUuid = manager.findByDiscordId(discordUserId);
                if (linkedUuid != null) {
                    whenPlayerIsOnline(linkedUuid, online -> {
                        manager.revokeVerification(linkedUuid, online);
                        if (online != null) {
                            online.sendMessage(Text.literal("Your Discord verification was revoked by a moderator.").formatted(Formatting.RED), false);
                        }
                    });
                }
            } else if ("remove".equals(subcommand)) {
                if (!DiscordBlacklist.remove(discordUserId)) {
                    event.reply(target.getName() + " wasn't blacklisted.").setEphemeral(true).queue();
                    return;
                }
                event.reply("Removed " + target.getName() + " from the blacklist.").setEphemeral(true).queue();
            }
        }
    }
}
