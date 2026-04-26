package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.util.Lang;
import dev.aerodev.sableprotect.util.SubLevelLookup;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ClaimCommand {

    private ClaimCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("claim")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return execute(player, name, registry);
                        }));
    }

    private static int execute(final ServerPlayer player, final String name, final ClaimRegistry registry) {
        // Check name uniqueness
        if (registry.isNameTaken(name)) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.claim.name_taken", name), false);
            return 0;
        }

        // Find targeted sub-level
        final SubLevel target = SubLevelLookup.getTargetedSubLevel(player);
        if (!(target instanceof ServerSubLevel serverSubLevel)) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.claim.no_target"), false);
            return 0;
        }

        // Check if already claimed
        final ClaimData existing = ClaimData.read(serverSubLevel);
        if (existing != null) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.claim.already_claimed", existing.getName()), false);
            return 0;
        }

        // Check minimum mass
        final double mass = serverSubLevel.getMassTracker().getMass();
        if (mass < SableProtectConfig.MINIMUM_CLAIM_MASS.get()) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.claim.too_small"), false);
            return 0;
        }

        // Create claim — register first (canonical), then mirror to userDataTag.
        final ClaimData data = new ClaimData(player.getUUID(), name);
        final var pos = serverSubLevel.logicalPose().position();
        data.setLastKnownPosition(new net.minecraft.world.phys.Vec3(pos.x(), pos.y(), pos.z()));
        registry.putClaim(serverSubLevel.getUniqueId(), data);
        ClaimData.write(serverSubLevel, data);

        player.displayClientMessage(
                Lang.tr("sableprotect.claim.success", name), false);
        return 1;
    }
}
