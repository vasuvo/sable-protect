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

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Decides whether a contraption-mounted block-breaker (Create drill, Create
 * Simulated rock cutting wheel) is allowed to break a block in a claimed
 * sub-level, and provides the thread-local context plumbing the drill mixin
 * needs to attribute the action.
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

    /** Thread-local stack of the current Create {@code MovementContext}. The drill
     *  mixin reads this from inside the {@code canBreak} override; the wrapper
     *  mixin on {@code BlockBreakingMovementBehaviour} pushes/pops it for the
     *  whole {@code tickBreaker} / {@code visitNewPosition} call. A stack rather
     *  than a single value because nested calls can occur in principle (Create
     *  doesn't currently nest, but the cost of a stack is negligible and saves a
     *  lifetime of "why did this break" debugging). */
    private static final ThreadLocal<Deque<Object>> CONTEXT_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    public static void pushContext(final Object context) {
        CONTEXT_STACK.get().push(context);
    }

    public static void popContext() {
        final Deque<Object> stack = CONTEXT_STACK.get();
        if (!stack.isEmpty()) stack.pop();
    }

    public static @Nullable Object peekContext() {
        final Deque<Object> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Apply the attribution rule for a drill: the {@code MovementContext} is
     * read from the thread-local pushed by the wrapper mixin. Returns true
     * (allow) when no context is on the stack — that path is reached only if
     * Create calls {@code canBreak} from somewhere we haven't wrapped, in which
     * case we'd rather miss a deny than break legitimate drilling.
     */
    public static boolean canDrillBreak(final Level breakerLevel, final BlockPos targetPos) {
        if (!SableProtectConfig.ENABLE_CONTRAPTION_BREAKER_PROTECTION.get()) return true;

        final Object context = peekContext();
        final BlockPos anchor = context == null ? null : tryGetContraptionAnchor(context);
        return canBreakerBreak(breakerLevel, targetPos, anchor);
    }

    /**
     * Apply the attribution rule given an explicit breaker anchor. Used by the
     * Create Simulated multi-mining mixin, where the supplier carries its own
     * world position rather than coming through a {@code MovementContext}.
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

    // -- Reflection plumbing for MovementContext / Contraption -----------------

    private static volatile @Nullable Field MOVEMENT_CONTEXT_CONTRAPTION;
    private static volatile @Nullable Field CONTRAPTION_ANCHOR;
    private static volatile boolean reflectionResolved;

    private static void resolveReflection(final Object context) {
        if (reflectionResolved) return;
        synchronized (ContraptionAttribution.class) {
            if (reflectionResolved) return;
            try {
                final Class<?> ctxClass = context.getClass();
                final Field contraptionField = ctxClass.getField("contraption");
                contraptionField.setAccessible(true);
                final Object contraption = contraptionField.get(context);
                if (contraption != null) {
                    Class<?> cls = contraption.getClass();
                    Field anchorField = null;
                    while (cls != null && anchorField == null) {
                        try { anchorField = cls.getField("anchor"); } catch (final NoSuchFieldException ignored) {}
                        if (anchorField == null) cls = cls.getSuperclass();
                    }
                    if (anchorField != null) {
                        anchorField.setAccessible(true);
                        CONTRAPTION_ANCHOR = anchorField;
                    }
                }
                MOVEMENT_CONTEXT_CONTRAPTION = contraptionField;
            } catch (final Throwable ignored) {
                // Leave fields null — getter will return null and the caller treats that as "no anchor".
            }
            reflectionResolved = true;
        }
    }

    private static @Nullable BlockPos tryGetContraptionAnchor(final Object context) {
        resolveReflection(context);
        final Field ctxField = MOVEMENT_CONTEXT_CONTRAPTION;
        final Field anchorField = CONTRAPTION_ANCHOR;
        if (ctxField == null || anchorField == null) return null;
        try {
            final Object contraption = ctxField.get(context);
            if (contraption == null) return null;
            final Object anchor = anchorField.get(contraption);
            return anchor instanceof BlockPos pos ? pos : null;
        } catch (final Throwable ignored) {
            return null;
        }
    }
}
