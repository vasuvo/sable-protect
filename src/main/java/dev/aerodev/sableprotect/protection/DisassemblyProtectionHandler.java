package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

public class DisassemblyProtectionHandler {

    private static final ResourceLocation PHYSICS_ASSEMBLER = ResourceLocation.parse("simulated:physics_assembler");
    private static final ResourceLocation MERGING_GLUE = ResourceLocation.parse("simulated:merging_glue");

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        final Block block = player.level().getBlockState(event.getPos()).getBlock();
        if (!isPhysicsAssembler(block)) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ctx.claimData().getRole(player.getUUID()) != ClaimRole.OWNER) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlaceBlock(final BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (!isMergingGlue(event.getPlacedBlock().getBlock())) return;

        final ClaimContext ctx = ProtectionHelper.getClaimContext(player.level(), event.getPos());
        if (ctx == null) return;

        if (ctx.claimData().getRole(player.getUUID()) != ClaimRole.OWNER) {
            event.setCanceled(true);
            ProtectionHelper.sendDeniedMessage(player);
        }
    }

    private static boolean isPhysicsAssembler(final Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).equals(PHYSICS_ASSEMBLER);
    }

    private static boolean isMergingGlue(final Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).equals(MERGING_GLUE);
    }
}
