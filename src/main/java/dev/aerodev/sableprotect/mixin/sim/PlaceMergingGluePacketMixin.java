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
 * Reject merging-glue placement onto a claim the player doesn't own. The glue placement
 * is initiated by Simulated-Project's client-side {@code MergingGlueItemHandler} which
 * dispatches a custom packet that calls {@code level.setBlockAndUpdate()} directly,
 * skipping {@code EntityPlaceEvent}.
 *
 * <p>Glue is placed at {@code parentPos.relative(parentFacing)} and
 * {@code childPos.relative(childFacing)}; we deny if either side is on a claimed sub-level
 * the player isn't the owner of (matches the design rule: you can merge your own ship with
 * an unclaimed one, but not with another player's claimed ship).
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.PlaceMergingGluePacket", remap = false)
public class PlaceMergingGluePacketMixin {

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
            PacketProtection.deniedComponent();
            // Send the message once at the end (denyOwnerOnly with sendMessage=false skips it).
            ctx.player().displayClientMessage(PacketProtection.deniedComponent(), true);
            ci.cancel();
        }
    }
}
