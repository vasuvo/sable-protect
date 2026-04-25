package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.util.BypassHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class BypassCommand {

    private BypassCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("bypass")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();

                    final int required = SableProtectConfig.ADMIN_BYPASS_PERMISSION_LEVEL.get();
                    if (required > 4 || !player.hasPermissions(required)) {
                        player.displayClientMessage(
                                Component.translatable("sableprotect.bypass.not_eligible", required), false);
                        return 0;
                    }

                    final boolean enabled = BypassHelper.toggle(player);
                    player.displayClientMessage(
                            Component.translatable(
                                    enabled ? "sableprotect.bypass.enabled" : "sableprotect.bypass.disabled"),
                            false);
                    return 1;
                });
    }
}
