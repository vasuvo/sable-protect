package dev.aerodev.sableprotect.util;

import dev.aerodev.sableprotect.claim.ClaimData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Crew-presence test shared by {@code /sp steal} (excludes the issuer) and
 * {@code /sp ground} (includes the issuer). Returns the UUID of any online crew
 * member found within the radius, or {@code null} if all crew are absent.
 *
 * <p>Players are considered <em>absent</em> when they're offline, in another
 * dimension than the ship, or outside the configured radius from the ship's
 * center. {@code excluding} (nullable) is the issuer to skip.
 */
public final class CrewPresence {

    private CrewPresence() {}

    public static @Nullable UUID findCrewWithinRadius(
            final MinecraftServer server,
            final ClaimData claim,
            final Vec3 shipCenter,
            final ResourceKey<Level> dimension,
            final long radiusSqr,
            final @Nullable UUID excluding) {

        final Set<UUID> crew = new HashSet<>();
        crew.add(claim.getOwner());
        crew.addAll(claim.getMembers());

        for (final UUID uuid : crew) {
            if (uuid.equals(excluding)) continue;
            final ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member == null) continue;
            if (!member.level().dimension().equals(dimension)) continue;
            final double dx = member.getX() - shipCenter.x;
            final double dy = member.getY() - shipCenter.y;
            final double dz = member.getZ() - shipCenter.z;
            if (dx * dx + dy * dy + dz * dz <= radiusSqr) return uuid;
        }
        return null;
    }
}
