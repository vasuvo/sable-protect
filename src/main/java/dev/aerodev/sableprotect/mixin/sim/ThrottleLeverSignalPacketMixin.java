package dev.aerodev.sableprotect.mixin.sim;

import dev.aerodev.sableprotect.protection.PacketProtection;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reject throttle-lever input from non-members on a claim with interactions protected.
 * Same {@code BlockHoldInteraction} bypass pattern as the steering wheel — silent denial
 * because the packet fires per input frame while held.
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.ThrottleLeverSignalPacket", remap = false)
public class ThrottleLeverSignalPacketMixin {

    @Shadow @Final private BlockPos pos;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableProtect$onHandle(final ServerPacketContext context, final CallbackInfo ci) {
        if (PacketProtection.denyInteraction(context.player(), this.pos, false)) {
            ci.cancel();
        }
    }
}
