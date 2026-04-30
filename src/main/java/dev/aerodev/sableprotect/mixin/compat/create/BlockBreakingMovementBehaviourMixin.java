package dev.aerodev.sableprotect.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.aerodev.sableprotect.util.ContraptionAttribution;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Wraps {@code BlockBreakingMovementBehaviour.tickBreaker} and {@code visitNewPosition}
 * so the current Create {@code MovementContext} is published on a thread-local while
 * {@code canBreak} runs. The drill-specific mixin reads that thread-local from inside
 * {@code DrillMovementBehaviour.canBreak} to attribute the break to a contraption
 * anchor and decide whether to deny it.
 *
 * <p>Saws, ploughs, rollers, and the harvester also extend this class and so will
 * also push/pop the context — but only the drill mixin acts on it. The other
 * breakers remain unrestricted, since their use cases (on-ship farms, decorative
 * clearing) are valuable enough to leave unprotected.
 *
 * <p>Sable already wraps {@code visitNewPosition} on this class for cross-sub-level
 * routing. Mixin composes multiple {@code @WrapMethod} on the same target into a
 * chain; the order doesn't matter for our purposes because we only need the
 * thread-local to be set whenever {@code canBreak} runs anywhere inside the call.
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour", remap = false)
public class BlockBreakingMovementBehaviourMixin {

    @WrapMethod(method = "tickBreaker")
    private void sableProtect$wrapTickBreaker(@Coerce final Object context,
                                               final Operation<Void> original) {
        ContraptionAttribution.pushContext(context);
        try {
            original.call(context);
        } finally {
            ContraptionAttribution.popContext();
        }
    }

    @WrapMethod(method = "visitNewPosition")
    private void sableProtect$wrapVisitNewPosition(@Coerce final Object context,
                                                    final BlockPos pos,
                                                    final Operation<Void> original) {
        ContraptionAttribution.pushContext(context);
        try {
            original.call(context, pos);
        } finally {
            ContraptionAttribution.popContext();
        }
    }
}
