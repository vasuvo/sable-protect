package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.permissions.Permissions;
import dev.aerodev.sableprotect.util.BypassHelper;
import dev.aerodev.sableprotect.util.Lang;
import dev.aerodev.sableprotect.util.NoMansLand;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class ProtectionHelper {

    private ProtectionHelper() {}

    public record ClaimContext(ServerSubLevel subLevel, ClaimData claimData) {}

    /**
     * Resolves claim context for a block position. Returns null if the block is not
     * in a sub-level or the sub-level is unclaimed.
     */
    public static @Nullable ClaimContext getClaimContext(final Level level, final BlockPos pos) {
        final SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            return null;
        }
        final ClaimData data = ClaimData.read(serverSubLevel);
        if (data == null) {
            return null;
        }
        // No Man's Land: claim still exists for /sp info / /sp myclaims, but protections do not apply.
        if (NoMansLand.contains(serverSubLevel)) {
            return null;
        }
        return new ClaimContext(serverSubLevel, data);
    }

    /**
     * Returns true if the player has both the configured permission level AND has opted in
     * via {@code /sp bypass}. The opt-in is per-session and resets on server restart, so
     * admins are subject to normal protection rules until they actively enable bypass.
     * Set the config value above 4 to disable the bypass entirely.
     */
    public static boolean isAdminBypass(final ServerPlayer player) {
        final int required = SableProtectConfig.ADMIN_BYPASS_PERMISSION_LEVEL.get();
        if (required > 4) return false;
        // LuckPerms node sableprotect.bypass.use overrides the level check; falls back
        // to the vanilla level when LP is absent or hasn't expressed an opinion.
        if (!Permissions.has(player, Permissions.Nodes.BYPASS_USE, required)) return false;
        return BypassHelper.isEnabled(player);
    }

    public static void sendDeniedMessage(final ServerPlayer player) {
        player.displayClientMessage(
                Lang.tr("sableprotect.protection.denied"),
                true
        );
    }
}
