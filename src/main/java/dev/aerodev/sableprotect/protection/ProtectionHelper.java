package dev.aerodev.sableprotect.protection;

import dev.aerodev.sableprotect.claim.ClaimData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class ProtectionHelper {

    private ProtectionHelper() {}

    public record ClaimContext(ServerSubLevel subLevel, ClaimData claimData) {}

    /**
     * Resolves claim context for a block position. Returns null if the block is not
     * in a sub-level or the sub-level is unclaimed.
     */
    public static @Nullable ClaimContext getClaimContext(final Level level, final BlockPos pos) {
        final SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            return null;
        }
        final ClaimData data = ClaimData.read(serverSubLevel);
        if (data == null) {
            return null;
        }
        return new ClaimContext(serverSubLevel, data);
    }

    public static void sendDeniedMessage(final ServerPlayer player) {
        player.displayClientMessage(
                Component.translatable("sableprotect.protection.denied"),
                true
        );
    }
}
