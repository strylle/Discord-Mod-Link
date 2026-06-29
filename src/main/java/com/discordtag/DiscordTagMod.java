package com.discordtag;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscordTagMod implements ModInitializer {

    public static final String MOD_ID = "discordtag";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DiscordTagManager MANAGER = new DiscordTagManager();
    public static volatile DiscordBotManager BOT_MANAGER;

    private final VerificationGate gate = new VerificationGate(MANAGER);

    public static DiscordBotManager runningBot() {
        DiscordBotManager bot = BOT_MANAGER;
        return (bot != null && bot.isRunning()) ? bot : null;
    }

    @Override
    public void onInitialize() {
        MANAGER.load();

        DiscordBotConfig botConfig = DiscordBotConfig.load();
        if (!botConfig.hasToken()) {
            throw new IllegalStateException(
                    "[DiscordTag] No bot token configured in discordtag-bot.json (in the config dir). "
                            + "Fill in 'token' and 'guildId', then restart the server.");
        }

        boolean lockEnabled = botConfig.enforcementMode() == DiscordBotConfig.Enforcement.LOCK;
        gate.register(lockEnabled);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            DiscordTagManager.PlayerEntry entry = MANAGER.getEntry(player.getUuid());

            if (entry == null) {
                player.sendMessage(buildFirstJoinPrompt(lockEnabled), false);
                return;
            }

            if (entry.enabled && entry.discordName != null && !entry.discordName.isEmpty()) {
                MANAGER.applyTag(player, entry.displayName(), entry.verified);
            }

            if (!entry.verified) {
                boolean hasClaimedName = entry.discordName != null && !entry.discordName.isEmpty();
                player.sendMessage(buildUnverifiedJoinPrompt(lockEnabled, hasClaimedName), false);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DiscordBotManager bot = new DiscordBotManager(MANAGER, botConfig);
            bot.start(server);
            BOT_MANAGER = bot;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DiscordBotManager bot = BOT_MANAGER;
            if (bot != null) {
                bot.stop();
                BOT_MANAGER = null;
            }
        });

        DiscordTagCommands.register();

        LOGGER.info("[DiscordTag] loaded.");
    }

    // clickable nudge shown the very first time a player joins
    private static MutableText buildFirstJoinPrompt(boolean lockEnabled) {
        MutableText clickToSet = Text.literal("[click to set it]").styled(style -> style
                .withFormatting(Formatting.LIGHT_PURPLE)
                .withUnderline(true)
                // note to self: this is the pre-1.21.5 ClickEvent API
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/discordtag set ")));

        String lead = lockEnabled
                ? "You must verify your Discord account before you can play. "
                : "Please verify your Discord account so admins know who's who. ";
        return Text.literal(lead)
                .formatted(lockEnabled ? Formatting.RED : Formatting.GRAY)
                .append(clickToSet);
    }

    // shown on every join while unverified
    private static MutableText buildUnverifiedJoinPrompt(boolean lockEnabled, boolean hasClaimedName) {
        MutableText actionHint = hasClaimedName
                ? Text.literal("/discordtag verify").styled(style -> style
                        .withFormatting(Formatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/discordtag verify")))
                : Text.literal("/discordtag set <username>").styled(style -> style
                        .withFormatting(Formatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/discordtag set ")));

        String lead = lockEnabled
                ? "You're not verified, so you can't move or interact yet. Run "
                : "Reminder: your Discord tag isn't verified yet. Run ";
        return Text.literal(lead)
                .formatted(lockEnabled ? Formatting.RED : Formatting.GRAY)
                .append(actionHint)
                .append(Text.literal(" to confirm it's really you.").formatted(lockEnabled ? Formatting.RED : Formatting.GRAY));
    }
}
