package dev.aerodev.sableprotect.util;

import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.protection.ProtectionHelper;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether a contraption-mounted block-breaker (Create drill, Create
 * Simulated rock cutting wheel) is allowed to break a block in a claimed
 * sub-level.
 *
 * <p>Attribution rule: a breaker is identified by the sub-level its host
 * contraption (or stationary controller) is anchored on. A breaker on the
 * same sub-level as the target may always proceed. A breaker whose host
 * sub-level is owned by a player who is owner-or-member of the target's
 * claim may proceed. Breakers anchored in the open world (no host sub-level)
 * cannot be attributed; their behaviour is gated by
 * {@link SableProtectConfig#ALLOW_EXTERNAL_ANCHOR_BREAKING}.
 */
public final class ContraptionAttribution {

    private ContraptionAttribution() {}

    /**
     * Apply the attribution rule given an explicit breaker anchor.
     */
    public static boolean canBreakerBreak(final Level breakerLevel, final BlockPos targetPos,
                                          @Nullable final BlockPos breakerAnchorPos) {
        if (!SableProtectConfig.ENABLE_CONTRAPTION_BREAKER_PROTECTION.get()) return true;

        final ClaimContext target = ProtectionHelper.getClaimContext(breakerLevel, targetPos);
        // Unclaimed, NML, or some other reason no claim context applies → allow.
        if (target == null) return true;

        final ClaimData targetClaim = target.claimData();
        if (!targetClaim.isBlocksProtected()) return true;

        final SubLevel hostSubLevel = breakerAnchorPos == null ? null
                : Sable.HELPER.getContaining(breakerLevel, breakerAnchorPos);

        if (hostSubLevel == null) {
            // Breaker is in the open world; no attribution possible.
            return SableProtectConfig.ALLOW_EXTERNAL_ANCHOR_BREAKING.get();
        }

        // Breaking blocks within your own ship is always fine.
        if (hostSubLevel.getUniqueId().equals(target.subLevel().getUniqueId())) return true;

        // Friendly host: the host sub-level's owner is owner-or-member of the target.
        if (hostSubLevel instanceof ServerSubLevel serverHost) {
            final ClaimData hostClaim = ClaimData.read(serverHost);
            if (hostClaim != null) {
                final ClaimRole role = targetClaim.getRole(hostClaim.getOwner());
                if (role == ClaimRole.OWNER || role == ClaimRole.MEMBER) return true;
            }
        }

        return false;
    }

    /** Reflectively read {@code MultiMiningSupplier.getLocation()} for the offroad
     *  mixin. The supplier interface lives in Create Simulated and we don't depend
     *  on it at compile time. */
    public static @Nullable BlockPos tryGetSupplierLocation(final Object supplier) {
        if (supplier == null) return null;
        try {
            final Object result = supplier.getClass().getMethod("getLocation").invoke(supplier);
            return result instanceof BlockPos pos ? pos : null;
        } catch (final Throwable ignored) {
            return null;
        }
    }
}
