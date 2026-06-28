package com.discordtag;

import net.fabricmc.api.ModInitializer;
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

    @Override
    public void onInitialize() {
        MANAGER.load();

        // on join we either silently re-apply their tag, or, ask them to set one up if this is their first time joining
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            DiscordTagManager.PlayerEntry entry = MANAGER.getEntry(player.getUuid());

            if (entry == null) {
                player.sendMessage(buildFirstJoinPrompt(), false);
            } else if (entry.enabled && entry.discordName != null && !entry.discordName.isEmpty()) {
                MANAGER.applyTag(player, entry.discordName);
            }
        });

        DiscordTagCommands.register();

        LOGGER.info("[DiscordTag] loaded.");
    }

    // clickable nudge shown the very first time a player joins
    private static MutableText buildFirstJoinPrompt() {
        MutableText clickToSet = Text.literal("[click to set it]").styled(style -> style
                .withFormatting(Formatting.LIGHT_PURPLE)
                .withUnderline(true)
                // note to self: this is the pre-1.21.5 ClickEvent API 
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/discordtag set ")));

        MutableText disableHint = Text.literal("/discordtag disable").styled(style -> style
                .withFormatting(Formatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/discordtag disable")));

        return Text.literal("Want your Discord username shown next to your name in the tab list? ")
                .formatted(Formatting.GRAY)
                .append(clickToSet)
                .append(Text.literal(" (or run ").formatted(Formatting.GRAY))
                .append(disableHint)
                .append(Text.literal(" to never see this message again)").formatted(Formatting.GRAY));
    }
}
