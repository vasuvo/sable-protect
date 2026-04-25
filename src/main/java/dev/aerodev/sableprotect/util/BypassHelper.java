package dev.aerodev.sableprotect.util;

import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player opt-in for the admin claim-protection bypass. Even players above the configured
 * permission level do <em>not</em> bypass protection by default — they must explicitly enable
 * it via {@code /sp bypass}. State is in-memory only and resets on server restart, so admins
 * always start a session subject to normal protection rules.
 */
public final class BypassHelper {

    private static final Set<UUID> bypassEnabled = new HashSet<>();

    private BypassHelper() {}

    /** Returns true if the player has explicitly enabled their admin bypass this session. */
    public static boolean isEnabled(final Player player) {
        return bypassEnabled.contains(player.getUUID());
    }

    /**
     * Toggles the bypass for the given player.
     * @return true if bypass is now enabled, false if disabled
     */
    public static boolean toggle(final Player player) {
        final UUID id = player.getUUID();
        if (bypassEnabled.remove(id)) {
            return false;
        }
        bypassEnabled.add(id);
        return true;
    }
}
