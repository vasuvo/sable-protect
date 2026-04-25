package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
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
        final ClaimRole role = data.getRole(player.getUUID());
        final boolean isOwner = role == ClaimRole.OWNER;
        final String name = data.getName();
        final String ownerName = resolvePlayerName(player, data.getOwner());

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);

        // Name — click to copy sub-level UUID
        player.displayClientMessage(Component.literal(name)
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

        // Protection flags — clickable toggles for owner, plain text for others
        player.displayClientMessage(formatFlag("Blocks", data.isBlocksProtected(), name, isOwner), false);
        player.displayClientMessage(formatFlag("Interactions", data.isInteractionsProtected(), name, isOwner), false);
        player.displayClientMessage(formatFlag("Inventories", data.isInventoriesProtected(), name, isOwner), false);

        // Owner-only action buttons
        if (isOwner) {
            player.displayClientMessage(
                    makeButton("[Rename]", "Click to rename",
                            ClickEvent.Action.SUGGEST_COMMAND,
                            "/sp edit " + name + " rename ")
                            .append(Component.literal("  "))
                            .append(makeButton("[Unclaim]", "Click to unclaim",
                                    ClickEvent.Action.SUGGEST_COMMAND,
                                    "/sp unclaim " + name)),
                    false);
        }

        // Owner
        MutableComponent ownerLine = Component.literal("Owner: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ownerName).withStyle(ChatFormatting.WHITE));
        if (isOwner) {
            ownerLine = ownerLine
                    .append(Component.literal("  "))
                    .append(makeButton("[Change Owner]", "Click to transfer ownership",
                            ClickEvent.Action.SUGGEST_COMMAND,
                            "/sp edit " + name + " changeowner "));
        }
        player.displayClientMessage(ownerLine, false);

        // Members
        MutableComponent membersHeader = Component.literal("Members")
                .withStyle(ChatFormatting.GRAY);
        if (isOwner) {
            membersHeader = membersHeader
                    .append(Component.literal(" "))
                    .append(makeButton("[Add Member]", "Click to add a member",
                            ClickEvent.Action.SUGGEST_COMMAND,
                            "/sp edit " + name + " addmember "));
        }
        membersHeader = membersHeader.append(Component.literal(":").withStyle(ChatFormatting.GRAY));

        if (data.getMembers().isEmpty()) {
            player.displayClientMessage(membersHeader
                    .append(Component.literal(" none").withStyle(ChatFormatting.GRAY)), false);
        } else {
            player.displayClientMessage(membersHeader, false);
            for (final UUID memberUuid : data.getMembers()) {
                final String memberName = resolvePlayerName(player, memberUuid);
                MutableComponent memberLine = Component.literal("  " + memberName)
                        .withStyle(ChatFormatting.WHITE);
                if (isOwner) {
                    memberLine = memberLine
                            .append(Component.literal("  "))
                            .append(makeButton("[Remove]", "Click to remove member",
                                    ClickEvent.Action.RUN_COMMAND,
                                    "/sp edit " + name + " removemember " + memberName));
                }
                player.displayClientMessage(memberLine, false);
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

    private static MutableComponent formatFlag(final String label, final boolean isProtected,
                                                final String claimName, final boolean isOwner) {
        final MutableComponent line = Component.literal(label + ": ")
                .withStyle(ChatFormatting.GRAY);

        final String statusText = isProtected ? "PROTECTED" : "UNPROTECTED";
        final ChatFormatting statusColor = isProtected ? ChatFormatting.GREEN : ChatFormatting.RED;

        if (isOwner) {
            // Clickable toggle — clicking flips the current state
            final String newState = isProtected ? "unprotected" : "protected";
            final String category = label.toLowerCase();
            line.append(Component.literal("[" + statusText + "]")
                    .withStyle(style -> style
                            .withColor(statusColor)
                            .withClickEvent(new ClickEvent(
                                    ClickEvent.Action.RUN_COMMAND,
                                    "/sp edit " + claimName + " " + category + " " + newState))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to toggle")))));
        } else {
            line.append(Component.literal(statusText).withStyle(statusColor));
        }

        return line;
    }

    private static MutableComponent makeButton(final String label, final String tooltip,
                                                final ClickEvent.Action action, final String command) {
        return Component.literal(label)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(action, command))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.literal(tooltip))));
    }

    static String resolvePlayerName(final ServerPlayer viewer, final UUID uuid) {
        final ServerPlayer target = viewer.getServer().getPlayerList().getPlayer(uuid);
        if (target != null) {
            return target.getGameProfile().getName();
        }
        return uuid.toString().substring(0, 8) + "...";
    }
}
