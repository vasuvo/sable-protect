package dev.aerodev.sableprotect.mixin.compat.vanilla;

import dev.aerodev.sableprotect.protection.ProtectionHelper;
import dev.aerodev.sableprotect.protection.ProtectionHelper.ClaimContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels small fireball block-impacts that would create a fire block inside a
 * claimed sub-level with Blocks protected. Closes the dispenser-fired fire
 * charge vector — the dispenser fires a {@link SmallFireball} projectile, which
 * lands and runs {@code level.setBlockAndUpdate(...)} with no player context
 * and no {@code BlockEvent.EntityPlaceEvent}, so nothing else can see it.
 *
 * <p>The fireball is still discarded normally by {@code onHit}; we only skip
 * the fire placement. Player-owned fireballs do not exist in vanilla (only
 * Blazes, Ghasts, and dispensers fire them), so denying without an actor check
 * doesn't break legitimate gameplay.
 */
@Mixin(SmallFireball.class)
public class SmallFireballMixin {

    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void sableProtect$gateFireBlockPlacement(final BlockHitResult result, final CallbackInfo ci) {
        final SmallFireball self = (SmallFireball) (Object) this;
        if (self.level().isClientSide) return;

        final BlockPos firePos = result.getBlockPos().relative(result.getDirection());
        final ClaimContext ctx = ProtectionHelper.getClaimContext(self.level(), firePos);
        if (ctx == null) return;
        if (!ctx.claimData().isBlocksProtected()) return;

        ci.cancel();
    }
}
