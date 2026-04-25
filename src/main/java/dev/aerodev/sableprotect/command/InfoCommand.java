package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.util.SubLevelLookup;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class InfoCommand {

    private InfoCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("info")
                // /sp info <name>
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (final String name : registry.getAllNames()) {
                                builder.suggest(name);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return executeByName(player, name, registry);
                        }))
                // /sp info (no args — look at target)
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return executeByLook(player);
                });
    }

    private static int executeByName(final ServerPlayer player, final String name, final ClaimRegistry registry) {
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

        sendInfoWindow(player, subLevel.getUniqueId(), data);
        return 1;
    }

    private static int executeByLook(final ServerPlayer player) {
        final SubLevel target = SubLevelLookup.getTargetedSubLevel(player);
        if (!(target instanceof ServerSubLevel serverSubLevel)) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.claim.no_target"), false);
            return 0;
        }

        final ClaimData data = ClaimData.read(serverSubLevel);
        if (data == null) {
            sendUnclaimedWindow(player, serverSubLevel.getUniqueId());
            return 1;
        }

        sendInfoWindow(player, serverSubLevel.getUniqueId(), data);
        return 1;
    }

    public static void sendInfoWindow(final ServerPlayer player, final UUID subLevelId, final ClaimData data) {
        final String ownerName = resolvePlayerName(player, data.getOwner());

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);

        // Name — click to copy sub-level UUID
        player.displayClientMessage(Component.literal(data.getName())
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.COPY_TO_CLIPBOARD,
                                subLevelId.toString()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy sub-level UUID")))),
                false);

        // Protection flags
        player.displayClientMessage(formatFlag("Blocks", data.isBlocksProtected()), false);
        player.displayClientMessage(formatFlag("Interactions", data.isInteractionsProtected()), false);
        player.displayClientMessage(formatFlag("Inventories", data.isInventoriesProtected()), false);

        // Owner
        player.displayClientMessage(Component.literal("Owner: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ownerName).withStyle(ChatFormatting.WHITE)), false);

        // Members
        if (data.getMembers().isEmpty()) {
            player.displayClientMessage(Component.literal("Members: none")
                    .withStyle(ChatFormatting.GRAY), false);
        } else {
            player.displayClientMessage(Component.literal("Members:")
                    .withStyle(ChatFormatting.GRAY), false);
            for (final UUID memberUuid : data.getMembers()) {
                final String memberName = resolvePlayerName(player, memberUuid);
                player.displayClientMessage(Component.literal("  " + memberName)
                        .withStyle(ChatFormatting.WHITE), false);
            }
        }

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);
    }

    private static void sendUnclaimedWindow(final ServerPlayer player, final UUID subLevelId) {
        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);

        // UUID as name — click to copy
        player.displayClientMessage(Component.literal(subLevelId.toString())
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.COPY_TO_CLIPBOARD,
                                subLevelId.toString()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy sub-level UUID")))),
                false);

        player.displayClientMessage(Component.translatable("sableprotect.info.unclaimed")
                .withStyle(ChatFormatting.GRAY), false);

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);
    }

    private static MutableComponent formatFlag(final String label, final boolean isProtected) {
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(isProtected ? "PROTECTED" : "UNPROTECTED")
                        .withStyle(isProtected ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static String resolvePlayerName(final ServerPlayer viewer, final UUID uuid) {
        final ServerPlayer target = viewer.getServer().getPlayerList().getPlayer(uuid);
        if (target != null) {
            return target.getGameProfile().getName();
        }
        return uuid.toString().substring(0, 8) + "...";
    }
}
