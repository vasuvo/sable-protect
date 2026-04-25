package dev.aerodev.sableprotect.mixin.sim;

import dev.aerodev.sableprotect.protection.PacketProtection;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reject spring placement onto a claim the player doesn't own. Same client-side bypass
 * pattern as merging glue.
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.PlaceSpringPacket", remap = false)
public class PlaceSpringPacketMixin {

    @Shadow @Final private BlockPos parentPos;
    @Shadow @Final private BlockPos childPos;
    @Shadow @Final private Direction parentFacing;
    @Shadow @Final private Direction childFacing;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableProtect$onHandle(final ServerPacketContext ctx, final CallbackInfo ci) {
        final BlockPos parentTarget = this.parentPos.relative(this.parentFacing);
        final BlockPos childTarget = this.childPos.relative(this.childFacing);
        if (PacketProtection.denyOwnerOnly(ctx.player(), parentTarget, false)
                || PacketProtection.denyOwnerOnly(ctx.player(), childTarget, false)) {
            ctx.player().displayClientMessage(PacketProtection.deniedComponent(), true);
            ci.cancel();
        }
    }
}
