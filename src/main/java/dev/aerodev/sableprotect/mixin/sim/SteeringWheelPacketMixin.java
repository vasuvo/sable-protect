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
 * Reject steering-wheel input from non-members on a claim with interactions protected.
 * The steering wheel uses Simulated-Project's {@code BlockHoldInteraction} framework which
 * polls input and dispatches packets every input frame, completely bypassing the standard
 * right-click event flow.
 *
 * <p>Denial is silent (no chat message) because this packet fires very frequently while a
 * player holds the wheel — chat would be spammed.
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.SteeringWheelPacket", remap = false)
public class SteeringWheelPacketMixin {

    @Shadow @Final private BlockPos pos;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableProtect$onHandle(final ServerPacketContext context, final CallbackInfo ci) {
        if (PacketProtection.denyInteraction(context.player(), this.pos, false)) {
            ci.cancel();
        }
    }
}
