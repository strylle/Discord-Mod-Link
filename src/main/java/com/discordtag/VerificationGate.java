package com.discordtag;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * two modes:
 *  - LOCK (default): unverified players are frozen in place and can't break/use/attack
 *  - NAG: unverified players play normally but get pestered on every join
 */
public class VerificationGate {

    private static final long REMINDER_COOLDOWN_MS = 5000;

    private final DiscordTagManager manager;
    private final Map<UUID, Vec3d> frozenPositions = new HashMap<>();
    private final Map<UUID, Long> lastReminder = new HashMap<>();
    private volatile boolean lockEnabled;

    public VerificationGate(DiscordTagManager manager) {
        this.manager = manager;
    }

    public void register(boolean lockEnabled) {
        this.lockEnabled = lockEnabled;

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> blockIfUnverified(player));
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> blockIfUnverified(player));
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!lockEnabled) {
                return TypedActionResult.pass(player.getStackInHand(hand));
            }
            DiscordTagManager.PlayerEntry entry = manager.getEntry(player.getUuid());
            if (entry != null && entry.verified) {
                return TypedActionResult.pass(player.getStackInHand(hand));
            }
            remind(player, entry);
            return TypedActionResult.fail(player.getStackInHand(hand));
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blockIfUnverified(player));
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !isLockedOut(player));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof PlayerEntity player) || !isLockedOut(player));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            frozenPositions.put(player.getUuid(), player.getPos());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            frozenPositions.remove(uuid);
            lastReminder.remove(uuid);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!this.lockEnabled) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                DiscordTagManager.PlayerEntry entry = manager.getEntry(player.getUuid());
                if (entry != null && entry.verified) {
                    frozenPositions.put(player.getUuid(), player.getPos());
                    continue;
                }
                Vec3d frozen = frozenPositions.get(player.getUuid());
                if (frozen == null) {
                    frozenPositions.put(player.getUuid(), player.getPos());
                } else if (player.getPos().squaredDistanceTo(frozen) > 0.0001) {
                    player.teleport(frozen.x, frozen.y, frozen.z, false);
                    remind(player, entry);
                }
            }
        });
    }

    private boolean isLockedOut(PlayerEntity player) {
        if (!lockEnabled) return false;
        DiscordTagManager.PlayerEntry entry = manager.getEntry(player.getUuid());
        return entry == null || !entry.verified;
    }

    private ActionResult blockIfUnverified(PlayerEntity player) {
        if (!lockEnabled) {
            return ActionResult.PASS;
        }
        DiscordTagManager.PlayerEntry entry = manager.getEntry(player.getUuid());
        if (entry != null && entry.verified) {
            return ActionResult.PASS;
        }
        remind(player, entry);
        return ActionResult.FAIL;
    }

    private void remind(PlayerEntity player, DiscordTagManager.PlayerEntry entry) {
        long now = System.currentTimeMillis();
        Long last = lastReminder.get(player.getUuid());
        if (last != null && now - last < REMINDER_COOLDOWN_MS) {
            return;
        }
        lastReminder.put(player.getUuid(), now);

        boolean hasClaimedName = entry != null && entry.discordName != null && !entry.discordName.isEmpty();
        String action = hasClaimedName ? "/discordtag verify" : "/discordtag set <username>";
        player.sendMessage(Text.literal(
                "You must verify your Discord account before you can play. Run " + action + " to start.")
                .formatted(Formatting.RED), true);
    }
}
