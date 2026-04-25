package dev.aerodev.sableprotect.permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Direct LuckPerms API bridge. <strong>Do not reference this class unless LuckPerms is
 * actually installed</strong> — its bytecode references LP types and the JVM will fail
 * to load it (NoClassDefFoundError) when LP is absent. {@link Permissions} gates every
 * call site behind a {@code ModList.isLoaded("luckperms")} check, so this class stays
 * unloaded in the LP-absent case.
 */
final class LuckPermsBridge {

    private LuckPermsBridge() {}

    /**
     * Returns {@link Tristate#TRUE} / {@link Tristate#FALSE} if LuckPerms has an explicit
     * decision for this node, or {@link Tristate#UNDEFINED} if it should fall through to
     * the vanilla OP check. Any LP failure (user not loaded, API thrown, …) is treated
     * as UNDEFINED so the caller falls back gracefully.
     */
    static Tristate query(final ServerPlayer player, final String node) {
        try {
            final LuckPerms api = LuckPermsProvider.get();
            final User user = api.getUserManager().getUser(player.getUUID());
            if (user == null) return Tristate.UNDEFINED;
            return user.getCachedData().getPermissionData().checkPermission(node);
        } catch (final Throwable t) {
            return Tristate.UNDEFINED;
        }
    }

    /** Coerce LP's tristate to a plain boolean. UNDEFINED becomes the supplied default. */
    static boolean asBoolean(final Tristate tristate, final boolean fallback) {
        return switch (tristate) {
            case TRUE -> true;
            case FALSE -> false;
            case UNDEFINED -> fallback;
        };
    }
}
