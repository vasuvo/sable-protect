package dev.aerodev.sableprotect.mixin.sim;

import com.llamalad7.mixinextras.sugar.Local;
import dev.aerodev.sableprotect.protection.PacketProtection;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reject rope-break requests targeting a rope whose start attachment is on a claimed
 * sub-level the player can't modify. The packet identifies the rope by UUID and the
 * handler resolves it to a {@code BlockPos} (the start attachment block); we capture
 * that local right before the destroy call and apply the Blocks-toggle protection.
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.RopeBreakPacket", remap = false)
public class RopeBreakPacketMixin {

    @Inject(
            method = "handle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
            ),
            cancellable = true,
            remap = false
    )
    private void sableProtect$onHandle(final ServerPacketContext ctx, final CallbackInfo ci,
                                       @Local final BlockPos blockAttachment) {
        if (PacketProtection.denyBlock(ctx.player(), blockAttachment, true)) {
            ci.cancel();
        }
    }
}
