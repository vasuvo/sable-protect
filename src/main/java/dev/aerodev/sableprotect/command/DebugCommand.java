package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.util.DebugHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class DebugCommand {

    private DebugCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("debug")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                    final boolean enabled = DebugHelper.toggle(player);
                    player.displayClientMessage(
                            Component.translatable(
                                    enabled ? "sableprotect.debug.enabled" : "sableprotect.debug.disabled"),
                            false);
                    return 1;
                });
    }
}
