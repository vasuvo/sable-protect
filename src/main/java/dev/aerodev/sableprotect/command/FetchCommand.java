package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

public final class FetchCommand {

    private FetchCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry,
                                                                      final FreezeManager freezeManager) {
        return Commands.literal("fetch")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            for (final UUID id : registry.getOwnedBy(player.getUUID())) {
                                final String n = registry.getNameByUuid(id);
                                if (n != null) builder.suggest(n);
                            }
                            for (final UUID id : registry.getMemberOf(player.getUUID())) {
                                final String n = registry.getNameByUuid(id);
                                if (n != null) builder.suggest(n);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return execute(player, name, registry, freezeManager);
                        }));
    }

    private static int execute(final ServerPlayer player, final String name,
                               final ClaimRegistry registry, final FreezeManager freezeManager) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(Component.translatable("sableprotect.not_found", name), false);
            return 0;
        }

        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        if (subLevel == null) {
            player.displayClientMessage(Component.translatable("sableprotect.not_loaded", name), false);
            return 0;
        }

        final ClaimData data = ClaimData.read(subLevel);
        if (data == null) {
            player.displayClientMessage(Component.translatable("sableprotect.not_found", name), false);
            return 0;
        }

        if (data.getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.fetch.not_authorized"), false);
            return 0;
        }

        final ServerLevel level = subLevel.getLevel();
        final WorldBorder border = level.getWorldBorder();
        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc currentPos = pose.position();

        if (isWithinBorder(border, currentPos.x(), currentPos.z())) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.fetch.inside_border"), false);
            return 0;
        }

        if (freezeManager.isFrozen(subLevelId)) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.fetch.already_frozen", name), false);
            return 0;
        }

        // Compute teleport target: clamp XZ inside border with configured inset, find a safe Y.
        final int inset = SableProtectConfig.FETCH_BORDER_INSET.get();
        final double targetX = clampInside(currentPos.x(), border.getMinX(), border.getMaxX(), inset);
        final double targetZ = clampInside(currentPos.z(), border.getMinZ(), border.getMaxZ(), inset);
        final int targetY = findSafeY(level, (int) Math.floor(targetX), (int) Math.floor(targetZ));

        final Vector3d destination = new Vector3d(targetX, targetY, targetZ);
        final Quaterniondc orientation = pose.orientation();

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.fetch.failed"), false);
            return 0;
        }
        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        pipeline.resetVelocity(subLevel);
        pipeline.teleport(subLevel, destination, orientation);

        final int durationSeconds = SableProtectConfig.FETCH_FREEZE_DURATION_SECONDS.get();
        final long durationTicks = durationSeconds * 20L;
        final long currentTick = level.getServer().getTickCount();

        final boolean frozen = freezeManager.freeze(subLevel, destination, orientation, durationTicks, currentTick);
        if (!frozen) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.fetch.freeze_unavailable"), false);
            return 0;
        }

        player.displayClientMessage(
                Component.translatable("sableprotect.fetch.success", name,
                        Component.literal((int) targetX + ", " + targetY + ", " + (int) targetZ)
                                .withStyle(ChatFormatting.AQUA),
                        durationSeconds),
                false);
        return 1;
    }

    private static boolean isWithinBorder(final WorldBorder border, final double x, final double z) {
        return x >= border.getMinX() && x <= border.getMaxX()
                && z >= border.getMinZ() && z <= border.getMaxZ();
    }

    private static double clampInside(final double value, final double min, final double max, final int inset) {
        // Pull the value to the nearest interior point with the given inset margin.
        final double interiorMin = min + inset;
        final double interiorMax = max - inset;
        if (interiorMin >= interiorMax) {
            // Border is too small for the inset; aim for the centre.
            return (min + max) / 2.0;
        }
        if (value < interiorMin) return interiorMin;
        if (value > interiorMax) return interiorMax;
        return value;
    }

    private static int findSafeY(final Level level, final int x, final int z) {
        // Use the world's heightmap surface as the drop point.
        final BlockPos surface = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z));
        return surface.getY() + 5; // Drop a few blocks above the surface to avoid clipping.
    }
}
