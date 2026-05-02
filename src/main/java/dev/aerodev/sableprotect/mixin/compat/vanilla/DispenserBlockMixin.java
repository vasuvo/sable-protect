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
 * via a path that bypasses {@code BlockEvent.EntityPlaceEvent}. Specifically:
 * <ul>
 *   <li>Flint &amp; steel — the vanilla dispense behavior calls
 *       {@code serverlevel.setBlockAndUpdate(...)} with a fire state directly.</li>
 *   <li>Lava bucket — {@code DispenseFluidContainer} calls
 *       {@code BucketItem.emptyContents}, which raw-{@code setBlock}s the fluid.</li>
 * </ul>
 *
 * <p>Fire charge dispensing is intentionally <em>not</em> cancelled here — the
 * dispenser fires a {@code SmallFireball} projectile that may land outside the
 * claim. {@link SmallFireballMixin} catches it at impact instead.
 *
 * <p>The cancel point is the {@code dispense} INVOKE inside
 * {@code DispenserBlock.dispenseFrom} — by that point the random slot has been
 * picked and we know which item is about to be dispensed. Cancelling makes the
 * dispenser a no-op for that tick (no item consumed, no projectile spawned),
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
     *  via raw setBlock. Fire charge is NOT here — it spawns a projectile that may
     *  travel before placing fire, and {@link SmallFireballMixin} handles that path. */
    private static boolean isBlockPlacingDispenseItem(final ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.LAVA_BUCKET);
    }
}
