package dev.aerodev.sableprotect.util;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class SubLevelLookup {

    private static final double STEP_SIZE = 0.25;
    private static final double MAX_REACH = 6.0;

    private SubLevelLookup() {}

    /**
     * Finds the nearest sub-level along the player's look direction by querying the
     * physics-based spatial index and raycasting through each candidate's local space.
     */
    public static @Nullable SubLevel getTargetedSubLevel(final Player player) {
        final Vec3 eyePos = player.getEyePosition();
        final Vec3 lookVec = player.getLookAngle();
        final Vec3 endPos = eyePos.add(lookVec.scale(MAX_REACH));
        final boolean debug = player instanceof ServerPlayer sp && DebugHelper.isEnabled(sp);

        // Query physics bounding boxes for sub-levels that intersect the ray's AABB
        final BoundingBox3d rayBounds = new BoundingBox3d(
                eyePos.x, eyePos.y, eyePos.z,
                endPos.x, endPos.y, endPos.z);
        final Iterable<SubLevel> candidates = Sable.HELPER.getAllIntersecting(player.level(), rayBounds);

        int candidateCount = 0;
        SubLevel closest = null;
        double closestDist = Double.MAX_VALUE;

        for (final SubLevel subLevel : candidates) {
            candidateCount++;

            // Transform ray endpoints into the sub-level's local/plot coordinate space
            final Vec3 localEye = subLevel.logicalPose().transformPositionInverse(eyePos);
            final Vec3 localEnd = subLevel.logicalPose().transformPositionInverse(endPos);
            final Vec3 localDir = localEnd.subtract(localEye);
            final double localLen = localDir.length();
            final Vec3 localDirNorm = localDir.normalize();

            // Step through local space looking for non-air blocks
            for (double d = 0; d <= localLen; d += STEP_SIZE) {
                final Vec3 localPoint = localEye.add(localDirNorm.scale(d));
                final BlockPos blockPos = BlockPos.containing(localPoint);

                if (!player.level().getBlockState(blockPos).isAir()) {
                    if (d < closestDist) {
                        closestDist = d;
                        closest = subLevel;
                    }
                    if (debug) {
                        sendDebug((ServerPlayer) player,
                                "Hit block at local " + blockPos.toShortString()
                                + " in sub-level " + subLevel.getUniqueId().toString().substring(0, 8)
                                + " (dist=" + String.format("%.2f", d) + ")");
                    }
                    break;
                }
            }
        }

        if (debug) {
            sendDebug((ServerPlayer) player,
                    "Lookup: " + candidateCount + " candidate(s), result="
                    + (closest != null ? closest.getUniqueId().toString().substring(0, 8) : "none"));
        }

        return closest;
    }

    private static void sendDebug(final ServerPlayer player, final String message) {
        player.displayClientMessage(
                Component.literal("[SP Debug] " + message).withStyle(ChatFormatting.DARK_GRAY),
                false);
    }
}
