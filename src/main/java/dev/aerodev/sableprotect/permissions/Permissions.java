package dev.aerodev.sableprotect.permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Permission gate for sable-protect's OP-level features. When LuckPerms is installed,
 * permission checks consult LP for the given node first; an explicit {@code TRUE} or
 * {@code FALSE} decision wins, while {@code UNDEFINED} falls back to the vanilla
 * permission level so existing OP-only setups keep working without configuration.
 *
 * <p>When LuckPerms is absent the vanilla level check is used directly.
 *
 * <h2>Permission nodes</h2>
 * Every node is namespaced under {@code sableprotect.}. See {@link Nodes} for the full
 * list; granting any of these in LuckPerms grants the corresponding feature. Denying
 * (e.g. {@code sableprotect.command.bypass = false}) revokes it even from OPs.
 */
public final class Permissions {

    /** Cached at first call. We don't want to query ModList on every permission check. */
    private static volatile @Nullable Boolean luckPermsAvailable = null;

    private Permissions() {}

    /** Returns true once if LuckPerms is loaded; cached for the rest of the session. */
    public static boolean isLuckPermsAvailable() {
        Boolean cached = luckPermsAvailable;
        if (cached != null) return cached;
        try {
            cached = ModList.get().isLoaded("luckperms");
        } catch (final Throwable t) {
            cached = false;
        }
        luckPermsAvailable = cached;
        return cached;
    }

    /**
     * Check whether {@code player} should be granted access to a feature.
     *
     * @param player        the player attempting the action
     * @param node          the LuckPerms permission node (e.g. {@code sableprotect.command.bypass})
     * @param fallbackLevel vanilla permission level to require when LP is absent or
     *                      hasn't expressed an opinion (typically {@code 2} for moderator
     *                      commands, {@code 4} for ops-only)
     */
    public static boolean has(final ServerPlayer player, final String node, final int fallbackLevel) {
        if (isLuckPermsAvailable()) {
            // The bridge class isn't loaded unless LP is on the classpath, avoiding
            // NoClassDefFoundError when LP is absent.
            final var tristate = LuckPermsBridge.query(player, node);
            return LuckPermsBridge.asBoolean(tristate, player.hasPermissions(fallbackLevel));
        }
        return player.hasPermissions(fallbackLevel);
    }

    /**
     * Convenience for command predicates. Console / non-player sources are accepted
     * iff they meet {@code fallbackLevel} on the source — LP nodes don't apply to
     * non-player command sources.
     */
    public static boolean has(final CommandSourceStack source, final String node, final int fallbackLevel) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return has(player, node, fallbackLevel);
        }
        return source.hasPermission(fallbackLevel);
    }

    /** Permission node constants for the OP-level features. */
    public static final class Nodes {
        public static final String COMMAND_DEBUG     = "sableprotect.command.debug";
        public static final String COMMAND_BYPASS    = "sableprotect.command.bypass";
        public static final String COMMAND_RELOAD    = "sableprotect.command.reload";
        public static final String COMMAND_CLAIMUUID = "sableprotect.command.claimuuid";
        /** Lets a non-owner edit any claim ({@code /sp edit ...}). */
        public static final String EDIT_OVERRIDE     = "sableprotect.edit.override";
        /** Eligibility to enable the protection bypass via {@code /sp bypass}. */
        public static final String BYPASS_USE        = "sableprotect.bypass.use";

        private Nodes() {}
    }
}
