package dev.aerodev.sableprotect.util;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class SubLevelLookup {

    private static final double STEP_SIZE = 0.25;
    private static final double MAX_REACH = 6.0;

    private SubLevelLookup() {}

    /**
     * Raycasts from the player's eye position along their look direction to find the
     * nearest sub-level within interaction range.
     */
    public static @Nullable SubLevel getTargetedSubLevel(final Player player) {
        final Vec3 eyePos = player.getEyePosition();
        final Vec3 lookVec = player.getLookAngle();

        for (double d = 0; d <= MAX_REACH; d += STEP_SIZE) {
            final Vec3 point = eyePos.add(lookVec.scale(d));
            final SubLevel subLevel = Sable.HELPER.getContaining(player.level(), point);
            if (subLevel != null) {
                return subLevel;
            }
        }
        return null;
    }
}
