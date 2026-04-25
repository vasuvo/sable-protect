package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.permissions.Permissions;
import dev.aerodev.sableprotect.util.Lang;
import dev.aerodev.sableprotect.util.NoMansLand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;

public final class ReloadCommand {

    private ReloadCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("reload")
                .requires(src -> Permissions.has(src, Permissions.Nodes.COMMAND_RELOAD, 2))
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();

                    // Refresh in-memory state pulled from the lang resource (in case the jar
                    // resource was swapped) and force NeoForge to re-read the config TOML
                    // (NeoForge's file watcher already does this on change, but an explicit
                    // call covers the case where edits were applied while the watcher missed).
                    Lang.reload();
                    forceConfigReload();

                    player.displayClientMessage(Lang.tr("sableprotect.reload.success"), false);
                    player.displayClientMessage(Lang.tr("sableprotect.reload.nml",
                            NoMansLand.isEnabled() ? "enabled" : "disabled",
                            SableProtectConfig.NO_MANS_LAND_MIN_X.get(),
                            SableProtectConfig.NO_MANS_LAND_MIN_Z.get(),
                            SableProtectConfig.NO_MANS_LAND_MAX_X.get(),
                            SableProtectConfig.NO_MANS_LAND_MAX_Z.get()), false);
                    return 1;
                });
    }

    private static void forceConfigReload() {
        try {
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
        } catch (final Throwable t) {
            SableProtectMod.LOGGER.warn("[sable-protect] Force config reload failed (file watcher may have already handled it): {}",
                    t.getMessage());
        }
    }
}
