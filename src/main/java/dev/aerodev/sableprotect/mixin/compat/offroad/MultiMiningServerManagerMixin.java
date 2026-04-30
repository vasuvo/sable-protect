package dev.aerodev.sableprotect.mixin.compat.offroad;

import dev.aerodev.sableprotect.util.ContraptionAttribution;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Denies the Create Simulated rock-cutting-wheel system the ability to register
 * a candidate breaking position when the target is in a claim its host borehead
 * bearing isn't a friendly contraption for. Returning false at intake means no
 * {@code BlockBreakingData} is created, no progress accumulates, and no
 * client-side mining-progress packet is sent.
 *
 * <p>The supplier is the {@code BoreheadBearingBlockEntity}, which exposes its
 * world position through {@code MultiMiningSupplier.getLocation()}. We read
 * that reflectively to avoid a compile-time dependency on Create Simulated's
 * offroad submodule.
 */
@Mixin(targets = "dev.ryanhcode.offroad.handlers.server.MultiMiningServerManager", remap = false)
public class MultiMiningServerManagerMixin {

    @Inject(method = "addOrRefreshPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ldev/ryanhcode/offroad/handlers/server/MultiMiningSupplier;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void sableProtect$gateAddOrRefresh(final Level level, final BlockPos pos,
                                                       @Coerce final Object supplier,
                                                       final CallbackInfoReturnable<Boolean> cir) {
        final BlockPos anchor = ContraptionAttribution.tryGetSupplierLocation(supplier);
        if (!ContraptionAttribution.canBreakerBreak(level, pos, anchor)) {
            cir.setReturnValue(false);
        }
    }
}
