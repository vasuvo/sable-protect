package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class InventoryProtectionHandler {

    private static final ResourceLocation STOCK_TICKER = ResourceLocation.parse("create:stock_ticker");

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (!isContainer(player, event)) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ctx.claimData().isInventoriesProtected() && ctx.claimData().getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    private static boolean isContainer(final ServerPlayer player, final PlayerInteractEvent.RightClickBlock event) {
        final BlockEntity blockEntity = player.level().getBlockEntity(event.getPos());
        if (blockEntity instanceof Container) {
            return true;
        }

        final Block block = player.level().getBlockState(event.getPos()).getBlock();
        return BuiltInRegistries.BLOCK.getKey(block).equals(STOCK_TICKER);
    }
}
