package dev.aerodev.sableprotect.mixin.compat.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.protection.ProtectionHelper;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gates {@link BucketItem} block-mutation paths against the Blocks toggle. Two
 * hooks are needed because cancelling {@code PlayerInteractEvent.RightClickBlock}
 * alone is insufficient for buckets: the vanilla client follows up
 * {@code ServerboundUseItemOnPacket} with {@code ServerboundUseItemPacket}, which
 * fires {@code RightClickItem} (not RightClickBlock) and runs {@code BucketItem.use}
 * regardless of whether we cancelled the earlier event. So we hook the actual
 * mutation points instead.
 *
 * <p>The two hooks together cover:
 * <ul>
 *   <li>Filled bucket emptying (lava, water, powdered snow) by player or dispenser
 *       — caught at {@code emptyContents(...)} HEAD. The dispenser path goes through
 *       the deprecated 4-arg overload which delegates to this 5-arg version, so the
 *       single mixin covers both.</li>
 *   <li>Empty bucket pickup of a fluid block (lava, water, snow) — caught by
 *       wrapping the {@code BucketPickup.pickupBlock(...)} invoke inside
 *       {@code use(...)}. We can't gate at {@code use} HEAD without re-doing the
 *       internal raytrace; wrapping the operation gives us the resolved target
 *       position for free.</li>
 * </ul>
 *
 * <p>Dispenser-driven empties (no Player) are denied unconditionally when the
 * destination is in a Blocks-protected claim — the dispenser has no role to check.
 */
@Mixin(BucketItem.class)
public class BucketItemMixin {

    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void sableProtect$gateEmpty(@Nullable final Player player, final Level level, final BlockPos pos,
                                         @Nullable final BlockHitResult result, @Nullable final ItemStack container,
                                         final CallbackInfoReturnable<Boolean> cir) {
        if (sableProtect$shouldDeny(player, level, pos)) {
            sableProtect$notify(player);
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(
            method = "use",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack sableProtect$gatePickup(final BucketPickup pickup, @Nullable final Player player,
                                               final LevelAccessor accessor, final BlockPos pos, final BlockState state,
                                               final Operation<ItemStack> original) {
        if (accessor instanceof Level level && sableProtect$shouldDeny(player, level, pos)) {
            sableProtect$notify(player);
            return ItemStack.EMPTY;
        }
        return original.call(pickup, player, accessor, pos, state);
    }

    @Unique
    private static boolean sableProtect$shouldDeny(@Nullable final Player player,
                                                    final Level level, final BlockPos pos) {
        final ClaimContext ctx = ProtectionHelper.getClaimContext(level, pos);
        if (ctx == null) return false;
        if (!ctx.claimData().isBlocksProtected()) return false;
        if (player == null) return true; // dispenser / unknown caller — default deny
        if (!(player instanceof ServerPlayer serverPlayer)) return false;
        if (ProtectionHelper.isAdminBypass(serverPlayer)) return false;
        return ctx.claimData().getRole(serverPlayer.getUUID()) == ClaimRole.DEFAULT;
    }

    @Unique
    private static void sableProtect$notify(@Nullable final Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ProtectionHelper.sendDeniedMessage(serverPlayer);
        }
    }
}
