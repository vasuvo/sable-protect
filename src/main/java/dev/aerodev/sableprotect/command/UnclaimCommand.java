package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class UnclaimCommand {

    private UnclaimCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("unclaim")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            for (final UUID id : registry.getOwnedBy(player.getUUID())) {
                                final String name = registry.getNameByUuid(id);
                                if (name != null) builder.suggest(name);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return promptConfirm(player, name, registry);
                        })
                        .then(Commands.literal("CONFIRM")
                                .executes(ctx -> {
                                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    final String name = StringArgumentType.getString(ctx, "name");
                                    return executeConfirmed(player, name, registry);
                                })));
    }

    private static int promptConfirm(final ServerPlayer player, final String name, final ClaimRegistry registry) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_found", name), false);
            return 0;
        }

        player.displayClientMessage(
                Component.translatable("sableprotect.unclaim.confirm", name), false);
        return 1;
    }

    private static int executeConfirmed(final ServerPlayer player, final String name, final ClaimRegistry registry) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_found", name), false);
            return 0;
        }

        // Find the sub-level across all dimensions
        final ServerSubLevel subLevel = findSubLevel(player, subLevelId);
        if (subLevel == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_loaded", name), false);
            return 0;
        }

        final ClaimData data = ClaimData.read(subLevel);
        if (data == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_found", name), false);
            return 0;
        }

        if (data.getRole(player.getUUID()) != ClaimRole.OWNER) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_owner"), false);
            return 0;
        }

        ClaimData.clear(subLevel);
        registry.remove(subLevelId);

        player.displayClientMessage(
                Component.translatable("sableprotect.unclaim.success", name), false);
        return 1;
    }

    static ServerSubLevel findSubLevel(final ServerPlayer player, final UUID subLevelId) {
        for (final ServerLevel level : player.getServer().getAllLevels()) {
            final SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            final SubLevel sub = container.getSubLevel(subLevelId);
            if (sub instanceof ServerSubLevel serverSub) {
                return serverSub;
            }
        }
        return null;
    }
}
