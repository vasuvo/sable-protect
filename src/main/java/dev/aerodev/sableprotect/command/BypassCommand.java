package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.permissions.Permissions;
import dev.aerodev.sableprotect.util.BypassHelper;
import dev.aerodev.sableprotect.util.Lang;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class BypassCommand {

    private BypassCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("bypass")
                .requires(src -> Permissions.has(src, Permissions.Nodes.COMMAND_BYPASS, 2))
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();

                    // Eligibility for the actual bypass effect: LP node sableprotect.bypass.use,
                    // or fall back to the configured permission level (default 4).
                    final int required = SableProtectConfig.ADMIN_BYPASS_PERMISSION_LEVEL.get();
                    final boolean eligible = required <= 4
                            && Permissions.has(player, Permissions.Nodes.BYPASS_USE, required);
                    if (!eligible) {
                        player.displayClientMessage(
                                Lang.tr("sableprotect.bypass.not_eligible", required), false);
                        return 0;
                    }

                    final boolean enabled = BypassHelper.toggle(player);
                    player.displayClientMessage(
                            Lang.tr(
                                    enabled ? "sableprotect.bypass.enabled" : "sableprotect.bypass.disabled"),
                            false);
                    return 1;
                });
    }
}
