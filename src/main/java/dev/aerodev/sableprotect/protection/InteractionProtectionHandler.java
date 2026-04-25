package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class InteractionProtectionHandler {

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isCanceled()) return; // Already handled by inventory or disassembly handler

        // Skip containers — InventoryProtectionHandler handles those
        final BlockEntity blockEntity = player.level().getBlockEntity(event.getPos());
        if (blockEntity instanceof Container) return;

        final BlockState state = player.level().getBlockState(event.getPos());

        // Doors and fence gates are always interactable (player movement only)
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ctx.claimData().isInteractionsProtected() && ctx.claimData().getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    @SubscribeEvent
    public void onEntityInteract(final PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        final SubLevel subLevel = Sable.HELPER.getContaining(event.getTarget());
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return;

        final ClaimData data = ClaimData.read(serverSubLevel);
        if (data == null) return;

        if (data.isInteractionsProtected() && data.getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    @SubscribeEvent
    public void onAttackEntity(final AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        final SubLevel subLevel = Sable.HELPER.getContaining(event.getTarget());
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return;

        final ClaimData data = ClaimData.read(serverSubLevel);
        if (data == null) return;

        if (data.isInteractionsProtected() && data.getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            event.setCanceled(true);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }
}
