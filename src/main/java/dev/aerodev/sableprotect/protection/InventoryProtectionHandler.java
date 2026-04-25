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
    private static final ResourceLocation BLAZE_BURNER = ResourceLocation.parse("create:blaze_burner");

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (!isInventoryBlock(player, event)) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ProtectionHelper.isAdminBypass(player)) return;

        if (ctx.claimData().isInventoriesProtected() && ctx.claimData().getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    private static boolean isInventoryBlock(final ServerPlayer player, final PlayerInteractEvent.RightClickBlock event) {
        final BlockEntity blockEntity = player.level().getBlockEntity(event.getPos());
        if (blockEntity instanceof Container) {
            return true;
        }

        final ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(event.getPos()).getBlock());
        return blockId.equals(STOCK_TICKER) || blockId.equals(BLAZE_BURNER);
    }
}
