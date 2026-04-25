package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3dc;

import java.util.UUID;

public final class LocateCommand {

    private LocateCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("locate")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            for (final UUID id : registry.getOwnedBy(player.getUUID())) {
                                final String n = registry.getNameByUuid(id);
                                if (n != null) builder.suggest(n);
                            }
                            for (final UUID id : registry.getMemberOf(player.getUUID())) {
                                final String n = registry.getNameByUuid(id);
                                if (n != null) builder.suggest(n);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return execute(player, name, registry);
                        }));
    }

    private static int execute(final ServerPlayer player, final String name, final ClaimRegistry registry) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_found", name), false);
            return 0;
        }

        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
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

        final ClaimRole role = data.getRole(player.getUUID());
        if (role == ClaimRole.DEFAULT) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.locate.not_authorized"), false);
            return 0;
        }

        final Vector3dc pos = subLevel.logicalPose().position();
        final int x = (int) Math.floor(pos.x());
        final int y = (int) Math.floor(pos.y());
        final int z = (int) Math.floor(pos.z());

        player.displayClientMessage(
                Component.translatable("sableprotect.locate.success", name,
                        Component.literal(x + ", " + y + ", " + z).withStyle(ChatFormatting.AQUA)),
                false);
        return 1;
    }
}
