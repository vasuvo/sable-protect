package dev.aerodev.sableprotect.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour;
import dev.aerodev.sableprotect.util.ContraptionAttribution;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gates Create's {@code canBreak} checks at every call site inside the base
 * {@link BlockBreakingMovementBehaviour}. When the actor is a
 * {@link DrillMovementBehaviour} drilling into a block whose claim doesn't
 * authorize the host contraption, we force the result to false. The base
 * {@code tickBreaker} responds to a false {@code canBreak} by clearing the
 * breaking-progress state and unstalling the contraption — the drill ship
 * slides past the protected block instead of grinding on it.
 *
 * <p>Saws, ploughs, rollers, and the harvester also extend
 * {@code BlockBreakingMovementBehaviour}, but the per-call instance check
 * scopes the deny to drills only. Their use cases (on-ship farms, surface
 * clearing) stay unrestricted.
 *
 * <p>Sable already wraps {@code visitNewPosition} on this class for
 * cross-sub-level routing, including a {@code findBreakingPos} lambda that
 * also calls {@code canBreak}. That lambda lives inside Sable's wrap method
 * body and is not the INVOKE we hook here, so candidate positions selected
 * via Sable's lambda may briefly be marked as the drill's target. The very
 * next {@code tickBreaker} pass re-checks {@code canBreak} via the INVOKE
 * we do hook and clears the state — no actual block break occurs.
 */
@Mixin(BlockBreakingMovementBehaviour.class)
public class BlockBreakingMovementBehaviourMixin {

    @WrapOperation(
            method = "tickBreaker",
            at = @At(value = "INVOKE",
                     target = "Lcom/simibubi/create/content/kinetics/base/BlockBreakingMovementBehaviour;canBreak(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean sableProtect$gateTickBreakerCanBreak(
            final BlockBreakingMovementBehaviour self,
            final Level world, final BlockPos pos, final BlockState state,
            final Operation<Boolean> original,
            @Local(argsOnly = true) final MovementContext context) {
        if (!original.call(self, world, pos, state)) return false;
        if (!(self instanceof DrillMovementBehaviour)) return true;
        return ContraptionAttribution.canBreakerBreak(context.world, pos, context.contraption.anchor);
    }

    @WrapOperation(
            method = "visitNewPosition",
            at = @At(value = "INVOKE",
                     target = "Lcom/simibubi/create/content/kinetics/base/BlockBreakingMovementBehaviour;canBreak(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean sableProtect$gateVisitNewPositionCanBreak(
            final BlockBreakingMovementBehaviour self,
            final Level world, final BlockPos pos, final BlockState state,
            final Operation<Boolean> original,
            @Local(argsOnly = true) final MovementContext context) {
        if (!original.call(self, world, pos, state)) return false;
        if (!(self instanceof DrillMovementBehaviour)) return true;
        return ContraptionAttribution.canBreakerBreak(context.world, pos, context.contraption.anchor);
    }
}
