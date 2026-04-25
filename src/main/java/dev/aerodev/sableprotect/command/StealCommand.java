package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.util.Lang;
import dev.aerodev.sableprotect.util.NoMansLand;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3dc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class StealCommand {

    private StealCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("steal")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            for (final String name : registry.getAllNames()) {
                                final UUID id = registry.getSubLevelByName(name);
                                if (id == null) continue;
                                final ServerSubLevel sub = UnclaimCommand.findSubLevel(player, id);
                                if (sub == null) continue;
                                if (!NoMansLand.contains(sub)) continue;
                                final ClaimData data = ClaimData.read(sub);
                                if (data == null) continue;
                                if (data.getOwner().equals(player.getUUID())) continue;
                                builder.suggest(registry.getNameByUuid(id));
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return promptConfirm(player, name, registry);
                        })
                        .then(Commands.literal("CONFIRM")
                                .executes(ctx -> {
                                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    final String name = StringArgumentType.getString(ctx, "name");
                                    return executeConfirmed(player, name, registry);
                                })));
    }

    private static int promptConfirm(final ServerPlayer player, final String name, final ClaimRegistry registry) {
        final ResolvedTarget target = resolve(player, name, registry);
        if (target == null) return 0;

        if (!preflightChecks(player, name, target)) return 0;

        player.displayClientMessage(
                Lang.tr("sableprotect.steal.confirm", name), false);
        return 1;
    }

    private static int executeConfirmed(final ServerPlayer player, final String name, final ClaimRegistry registry) {
        final ResolvedTarget target = resolve(player, name, registry);
        if (target == null) return 0;

        if (!preflightChecks(player, name, target)) return 0;

        // Snapshot the previous owner + members for notification BEFORE we mutate.
        final Set<UUID> notify = new HashSet<>();
        notify.add(target.data.getOwner());
        notify.addAll(target.data.getMembers());

        // Transfer ownership and clear members; toggles + name are preserved.
        target.data.setOwner(player.getUUID());
        target.data.getMembers().clear();
        registry.touchClaim(target.subLevel.getUniqueId());
        ClaimData.write(target.subLevel, target.data);

        // Notify previous owner + members in red. Skips offline players silently.
        final Component warning = Lang.tr(
                "sableprotect.steal.notification", player.getGameProfile().getName(), name)
                .withStyle(ChatFormatting.RED);
        for (final UUID uuid : notify) {
            if (uuid.equals(player.getUUID())) continue;
            final ServerPlayer other = player.getServer().getPlayerList().getPlayer(uuid);
            if (other != null) other.displayClientMessage(warning, false);
        }

        player.displayClientMessage(
                Lang.tr("sableprotect.steal.success", name), false);
        InfoCommand.sendInfoWindow(player, target.subLevel.getUniqueId(), target.subLevel, target.data);
        return 1;
    }

    private record ResolvedTarget(ServerSubLevel subLevel, ClaimData data) {}

    private static ResolvedTarget resolve(final ServerPlayer player, final String name,
                                          final ClaimRegistry registry) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(Lang.tr("sableprotect.not_found", name), false);
            return null;
        }
        final ClaimData data = registry.getClaim(subLevelId);
        if (data == null) {
            player.displayClientMessage(Lang.tr("sableprotect.not_found", name), false);
            return null;
        }
        // Steal requires the ship to be loaded — on-board check needs a real sub-level.
        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        if (subLevel == null) {
            player.displayClientMessage(Lang.tr("sableprotect.not_loaded", name), false);
            return null;
        }
        return new ResolvedTarget(subLevel, data);
    }

    /**
     * Checks all conditions required for a steal. Sends an appropriate failure message and
     * returns false if any check fails; returns true if the steal is permitted.
     */
    private static boolean preflightChecks(final ServerPlayer player, final String name,
                                           final ResolvedTarget target) {
        if (target.data.getOwner().equals(player.getUUID())) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.steal.already_owner"), false);
            return false;
        }

        if (!NoMansLand.isEnabled() || !NoMansLand.contains(target.subLevel)) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.steal.not_in_nml", name), false);
            return false;
        }

        // Player must be physically on board the target sub-level.
        final SubLevel ridingOrTracking = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (ridingOrTracking == null
                || !ridingOrTracking.getUniqueId().equals(target.subLevel.getUniqueId())) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.steal.not_on_board", name), false);
            return false;
        }

        // Owner or any member within the configured radius blocks the steal.
        final UUID blocker = findPresentOwnerOrMember(player, target);
        if (blocker != null) {
            final ServerPlayer blockerPlayer = player.getServer().getPlayerList().getPlayer(blocker);
            final String blockerName = blockerPlayer != null
                    ? blockerPlayer.getGameProfile().getName()
                    : blocker.toString().substring(0, 8);
            player.displayClientMessage(
                    Lang.tr("sableprotect.steal.crew_present", blockerName), false);
            return false;
        }

        return true;
    }

    /**
     * Returns the UUID of any online owner or member within the configured absence radius
     * of the target sub-level's center. Returns null if the entire crew is absent (offline
     * or far away).
     */
    private static UUID findPresentOwnerOrMember(final ServerPlayer initiator, final ResolvedTarget target) {
        final int radius = SableProtectConfig.STEAL_ABSENCE_RADIUS.get();
        final long radiusSqr = (long) radius * radius;
        final Vector3dc shipCenter = target.subLevel.logicalPose().position();

        final Set<UUID> crew = new HashSet<>();
        crew.add(target.data.getOwner());
        crew.addAll(target.data.getMembers());

        for (final UUID uuid : crew) {
            if (uuid.equals(initiator.getUUID())) continue; // The initiator's own presence doesn't block them.
            final ServerPlayer member = initiator.getServer().getPlayerList().getPlayer(uuid);
            if (member == null) continue; // Offline → absent.
            if (member.level() != target.subLevel.getLevel()) continue; // Different dimension → absent.
            final double dx = member.getX() - shipCenter.x();
            final double dy = member.getY() - shipCenter.y();
            final double dz = member.getZ() - shipCenter.z();
            if (dx * dx + dy * dy + dz * dz <= radiusSqr) {
                return uuid;
            }
        }
        return null;
    }
}
