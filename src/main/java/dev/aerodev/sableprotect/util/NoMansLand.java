package dev.aerodev.sableprotect.util;

import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3dc;

/**
 * Configurable XZ rectangle within which claim protections are suspended and ships can be
 * stolen. Backed entirely by {@link SableProtectConfig}; helpers normalize the rectangle
 * (so users can write the corners in either order) and short-circuit when disabled.
 */
public final class NoMansLand {

    private NoMansLand() {}

    /** Whether the No Man's Land region is currently active. */
    public static boolean isEnabled() {
        return SableProtectConfig.NO_MANS_LAND_ENABLED.get();
    }

    /**
     * Returns true if the given XZ position is inside the configured rectangle. Always
     * false when NML is disabled; the rectangle is treated as inclusive on all bounds.
     */
    public static boolean contains(final double x, final double z) {
        if (!isEnabled()) return false;
        final int x1 = SableProtectConfig.NO_MANS_LAND_MIN_X.get();
        final int x2 = SableProtectConfig.NO_MANS_LAND_MAX_X.get();
        final int z1 = SableProtectConfig.NO_MANS_LAND_MIN_Z.get();
        final int z2 = SableProtectConfig.NO_MANS_LAND_MAX_Z.get();
        final double minX = Math.min(x1, x2);
        final double maxX = Math.max(x1, x2);
        final double minZ = Math.min(z1, z2);
        final double maxZ = Math.max(z1, z2);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Whether the sub-level's current center position is inside the No Man's Land rectangle. */
    public static boolean contains(final ServerSubLevel subLevel) {
        final Vector3dc pos = subLevel.logicalPose().position();
        return contains(pos.x(), pos.z());
    }
}
