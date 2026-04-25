package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.permissions.Permissions;
import dev.aerodev.sableprotect.util.DebugHelper;
import dev.aerodev.sableprotect.util.Lang;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class DebugCommand {

    private DebugCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("debug")
                .requires(src -> Permissions.has(src, Permissions.Nodes.COMMAND_DEBUG, 2))
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                    final boolean enabled = DebugHelper.toggle(player);
                    player.displayClientMessage(
                            Lang.tr(
                                    enabled ? "sableprotect.debug.enabled" : "sableprotect.debug.disabled"),
                            false);
                    return 1;
                });
    }
}
