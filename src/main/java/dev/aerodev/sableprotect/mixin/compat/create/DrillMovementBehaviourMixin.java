package dev.aerodev.sableprotect.mixin.compat.create;

import dev.aerodev.sableprotect.util.ContraptionAttribution;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Denies a drill the ability to break blocks in claims it isn't a friendly host
 * for. Returning false from {@code canBreak} makes the base
 * {@code BlockBreakingMovementBehaviour.tickBreaker} clear the breaking state
 * and unstall the contraption naturally — the drill will keep moving forward
 * past the protected block instead of grinding on it.
 *
 * <p>The {@code MovementContext} needed to identify the host contraption is
 * not visible from {@code canBreak}, so we read it from a thread-local
 * published by {@link BlockBreakingMovementBehaviourMixin}.
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour", remap = false)
public class DrillMovementBehaviourMixin {

    @Inject(method = "canBreak", at = @At("RETURN"), cancellable = true, remap = false)
    private void sableProtect$gateCanBreak(final Level world, final BlockPos pos, final BlockState state,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        if (!ContraptionAttribution.canDrillBreak(world, pos)) {
            cir.setReturnValue(false);
        }
    }
}
