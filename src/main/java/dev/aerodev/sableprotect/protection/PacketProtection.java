package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.util.NoMansLand;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Helper used by mixins that intercept Create / Simulated-Project packet handlers. Each
 * method resolves the target sub-level via Sable, looks up the claim, and decides whether
 * the packet should be denied based on the player's role and the claim's toggles.
 *
 * <p>All methods return {@code true} if the action should be denied (mixin should cancel
 * the packet handler), {@code false} if it should proceed.
 *
 * <p>Admin bypass via {@code /sp bypass} short-circuits these checks identically to the
 * standard {@link ProtectionHelper#isAdminBypass} path.
 */
public final class PacketProtection {

    private PacketProtection() {}

    /**
     * Owner-only check — used for actions that should never be permitted to anyone but
     * the claim owner (Physics Assembler trigger, merging glue / spring placement).
     */
    public static boolean denyOwnerOnly(final ServerPlayer player, final BlockPos pos, final boolean sendMessage) {
        final ClaimData claim = resolveClaim(player.level(), pos);
        if (claim == null) return false;
        if (ProtectionHelper.isAdminBypass(player)) return false;
        if (claim.getRole(player.getUUID()) == ClaimRole.OWNER) return false;
        if (sendMessage) ProtectionHelper.sendDeniedMessage(player);
        return true;
    }

    /** Interactions toggle — denies non-members when the claim has interactions protected. */
    public static boolean denyInteraction(final ServerPlayer player, final BlockPos pos, final boolean sendMessage) {
        final ClaimData claim = resolveClaim(player.level(), pos);
        if (claim == null) return false;
        if (!claim.isInteractionsProtected()) return false;
        if (ProtectionHelper.isAdminBypass(player)) return false;
        if (claim.getRole(player.getUUID()) != ClaimRole.DEFAULT) return false;
        if (sendMessage) ProtectionHelper.sendDeniedMessage(player);
        return true;
    }

    /** Blocks toggle — denies non-members when the claim has blocks protected. */
    public static boolean denyBlock(final ServerPlayer player, final BlockPos pos, final boolean sendMessage) {
        final ClaimData claim = resolveClaim(player.level(), pos);
        if (claim == null) return false;
        if (!claim.isBlocksProtected()) return false;
        if (ProtectionHelper.isAdminBypass(player)) return false;
        if (claim.getRole(player.getUUID()) != ClaimRole.DEFAULT) return false;
        if (sendMessage) ProtectionHelper.sendDeniedMessage(player);
        return true;
    }

    /**
     * Same as {@link #denyOwnerOnly} but used from client-side mixins that need to know
     * whether the local player would be denied so they can short-circuit BEFORE sending
     * a packet. The {@code level} param is the local level; no server-side denial message.
     */
    public static boolean isOnProtectedSubLevel(final Level level, final BlockPos pos) {
        final SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (!(subLevel instanceof ServerSubLevel server)) return subLevel != null && hasClaimReflective(subLevel);
        return ClaimData.read(server) != null;
    }

    private static ClaimData resolveClaim(final Level level, final BlockPos pos) {
        final SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return null;
        if (NoMansLand.contains(serverSubLevel)) return null;
        return ClaimData.read(serverSubLevel);
    }

    /**
     * Client-side fallback: when {@code SubLevel} isn't a {@code ServerSubLevel}, we can't
     * read claim data directly. Conservatively assume any sub-level on the client *might*
     * be claimed and let server-side enforcement decide. Returning false here would let
     * the client-side optimistic action proceed; returning true cancels it preemptively.
     */
    @SuppressWarnings("unused")
    private static boolean hasClaimReflective(final SubLevel subLevel) {
        return true;
    }

    /** Convenience constant for the "denied" message component. */
    public static Component deniedComponent() {
        return Component.translatable("sableprotect.protection.denied");
    }
}
