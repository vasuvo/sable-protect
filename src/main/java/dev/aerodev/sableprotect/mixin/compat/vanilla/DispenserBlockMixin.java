package dev.aerodev.sableprotect.mixin.compat.vanilla;

import com.llamalad7.mixinextras.sugar.Local;
import dev.aerodev.sableprotect.protection.ProtectionHelper;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels dispenser actions that would place a block inside a claimed sub-level
 * via a path that bypasses {@code BlockEvent.EntityPlaceEvent}. Currently this
 * is just the flint &amp; steel dispense behavior, which calls
 * {@code serverlevel.setBlockAndUpdate(...)} with a fire state directly.
 *
 * <p>Other dispenser-driven paths are caught at their actual mutation point
 * instead, since cancelling at intake here is too coarse:
 * <ul>
 *   <li>Lava / water bucket dispense → {@code BucketItemMixin} on
 *       {@code emptyContents(...)} — also covers held-bucket and the deprecated
 *       4-arg overload that {@code DispenseFluidContainer} calls into.</li>
 *   <li>Fire charge dispense → {@link SmallFireballMixin} on the projectile's
 *       block-impact, since the projectile may travel outside the claim before
 *       landing.</li>
 * </ul>
 *
 * <p>The cancel point is the {@code dispense} INVOKE inside
 * {@code DispenserBlock.dispenseFrom} — by that point the random slot has been
 * picked and we know which item is about to be dispensed. Cancelling makes the
 * dispenser a no-op for that tick (no item consumed, no fire placed),
 * matching the behavior of a dispenser containing an item with no behavior.
 */
@Mixin(DispenserBlock.class)
public class DispenserBlockMixin {

    @Inject(
            method = "dispenseFrom",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;dispense(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"),
            cancellable = true)
    private void sableProtect$gateBlockPlacingDispense(final ServerLevel level, final BlockState state, final BlockPos pos,
                                                        final CallbackInfo ci,
                                                        @Local final ItemStack itemstack) {
        if (!isBlockPlacingDispenseItem(itemstack)) return;

        final Direction facing = state.getValue(DispenserBlock.FACING);
        final BlockPos targetPos = pos.relative(facing);

        final ClaimContext ctx = ProtectionHelper.getClaimContext(level, targetPos);
        if (ctx == null) return;
        if (!ctx.claimData().isBlocksProtected()) return;

        ci.cancel();
    }

    /** Items whose dispenser behavior writes a block at {@code pos.relative(facing)}
     *  via raw setBlock. Buckets and fire charges have item-/projectile-level mixins
     *  for their own paths; only flint &amp; steel needs to be caught here. */
    private static boolean isBlockPlacingDispenseItem(final ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL);
    }
}
