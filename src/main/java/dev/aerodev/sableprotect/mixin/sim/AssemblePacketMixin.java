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
 * Reject the Physics Assembler activation packet for non-owners on a claimed sub-level.
 * The Physics Assembler interaction is dispatched by Simulated-Project's client-side
 * {@code PhysicsAssemblerGUIHandler} via {@code VeilPacketManager}, bypassing the standard
 * {@code RightClickBlock} flow that the rest of our protection relies on.
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.AssemblePacket", remap = false)
public class AssemblePacketMixin {

    @Shadow @Final private BlockPos pos;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableProtect$onHandle(final ServerPacketContext context, final CallbackInfo ci) {
        if (PacketProtection.denyOwnerOnly(context.player(), this.pos, true)) {
            ci.cancel();
        }
    }
}
