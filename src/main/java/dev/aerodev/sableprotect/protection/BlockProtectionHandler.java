package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

public class BlockProtectionHandler {

    @SubscribeEvent
    public void onBreakBlock(final BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ProtectionHelper.isAdminBypass(player)) return;

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

        if (ProtectionHelper.isAdminBypass(player)) return;

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

    /**
     * Gate held items that create blocks (fire, lava) without going through the standard
     * BlockItem placement pathway — flint &amp; steel, fire charge, and lava bucket all
     * call {@code level.setBlock(...)} directly, so {@code BlockEvent.EntityPlaceEvent}
     * never fires and the place handler above misses them. We catch them at
     * {@code RightClickBlock} (which always fires for item-on-block) and gate by the
     * Blocks toggle independently of Interactions, so an owner who allows visitors to
     * press buttons but not damage the ship doesn't accidentally allow fire grief.
     *
     * <p>Runs at HIGH priority so it precedes {@link InteractionProtectionHandler} —
     * the latter would also cancel via the Interactions toggle when set, but we want
     * the cancel/denied-message path to come from the right reason regardless of
     * which toggle is the gate.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickWithBlockPlacingItem(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isCanceled()) return;

        final ItemStack stack = event.getItemStack();
        if (!isBlockPlacingItem(stack)) return;

        final BlockPos pos = event.getPos();
        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), pos);
        if (ctx == null) return;

        if (ProtectionHelper.isAdminBypass(player)) return;

        if (ctx.claimData().isBlocksProtected()
                && ctx.claimData().getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    /** Items that write a block via {@code level.setBlock(...)} without firing
     *  {@code EntityPlaceEvent}. Kept narrow on purpose — water/snow buckets are not
     *  fire vectors and aren't included; powdered snow likewise. Add new entries only
     *  when you've verified the item bypasses the place event. */
    private static boolean isBlockPlacingItem(final ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL)
                || stack.is(Items.FIRE_CHARGE)
                || stack.is(Items.LAVA_BUCKET);
    }
}
