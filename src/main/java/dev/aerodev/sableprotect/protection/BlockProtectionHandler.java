package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

public class BlockProtectionHandler {

    @SubscribeEvent
    public void onBreakBlock(final BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ctx.claimData().isBlocksProtected() && ctx.claimData().getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    @SubscribeEvent
    public void onPlaceBlock(final BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ctx.claimData().isBlocksProtected() && ctx.claimData().getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    @SubscribeEvent
    public void onExplosion(final ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(blockPos -> {
            final ClaimContext ctx = ProtectionHelper.getClaimContext(event.getLevel(), blockPos);
            return ctx != null && ctx.claimData().isBlocksProtected();
        });
    }
}
