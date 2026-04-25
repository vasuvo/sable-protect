package dev.aerodev.sableprotect.command;

import dev.aerodev.sableprotect.claim.ClaimRegistry;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class SpCommand {

    private SpCommand() {}

    public static void register(final RegisterCommandsEvent event, final ClaimRegistry registry) {
        event.getDispatcher().register(
                Commands.literal("sp")
                        .then(ClaimCommand.register(registry))
                        .then(UnclaimCommand.register(registry))
                        .then(InfoCommand.register(registry))
        );
    }
}
