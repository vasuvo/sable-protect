package dev.aerodev.sableprotect.command;

import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class SpCommand {

    private SpCommand() {}

    public static void register(final RegisterCommandsEvent event,
                                final ClaimRegistry registry,
                                final FreezeManager freezeManager) {
        event.getDispatcher().register(
                Commands.literal("sp")
                        .then(ClaimCommand.register(registry))
                        .then(UnclaimCommand.register(registry))
                        .then(InfoCommand.register(registry))
                        .then(EditCommand.register(registry))
                        .then(MyClaimsCommand.register(registry))
                        .then(LocateCommand.register(registry))
                        .then(FetchCommand.register(registry, freezeManager))
                        .then(StealCommand.register(registry))
                        .then(DebugCommand.register())
                        .then(BypassCommand.register())
                        .then(ReloadCommand.register())
                        .then(ClaimUuidCommand.register(registry))
        );
    }
}
