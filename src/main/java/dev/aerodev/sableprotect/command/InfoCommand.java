package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.util.CrewPresence;
import dev.aerodev.sableprotect.util.Lang;
import dev.aerodev.sableprotect.util.NoMansLand;
import dev.aerodev.sableprotect.util.SubLevelLookup;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;
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
                    Lang.tr("sableprotect.not_found", name), false);
            return 0;
        }

        final ClaimData data = registry.getClaim(subLevelId);
        if (data == null) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.not_found", name), false);
            return 0;
        }

        // Sub-level may be unloaded; sendInfoWindow handles that gracefully.
        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        sendInfoWindow(player, subLevelId, subLevel, data);
        return 1;
    }

    private static int executeByLook(final ServerPlayer player) {
        final SubLevel target = SubLevelLookup.getTargetedSubLevel(player);
        if (!(target instanceof ServerSubLevel serverSubLevel)) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.claim.no_target"), false);
            return 0;
        }

        final ClaimData data = ClaimData.read(serverSubLevel);
        if (data == null) {
            sendUnclaimedWindow(player, serverSubLevel.getUniqueId());
            return 1;
        }

        sendInfoWindow(player, serverSubLevel.getUniqueId(), serverSubLevel, data);
        return 1;
    }

    public static void sendInfoWindow(final ServerPlayer player, final UUID subLevelId,
                                      final @Nullable ServerSubLevel subLevel, final ClaimData data) {
        final ClaimRole role = data.getRole(player.getUUID());
        final boolean isOwner = role == ClaimRole.OWNER;
        final boolean isMemberOrOwner = role == ClaimRole.OWNER || role == ClaimRole.MEMBER;
        final String name = data.getName();
        final String ownerName = resolvePlayerName(player, data.getOwner());

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);

        // Name — click to copy sub-level UUID; Locate / Fetch / Steal shown contextually.
        MutableComponent header = Component.literal(name)
                .withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.COPY_TO_CLIPBOARD,
                                subLevelId.toString()))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy sub-level UUID"))));

        // Position-dependent annotations (NML, world border, on-board) require a loaded sub-level.
        final boolean loaded = subLevel != null;
        final boolean inNoMansLand = loaded && NoMansLand.contains(subLevel);

        if (!loaded) {
            header = header
                    .append(Component.literal(" "))
                    .append(Component.literal("[unloaded]")
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.GRAY)
                                    .withItalic(true)
                                    .withHoverEvent(new HoverEvent(
                                            HoverEvent.Action.SHOW_TEXT,
                                            Lang.tr("sableprotect.info.unloaded_hover")))));
        }

        if (inNoMansLand) {
            header = header
                    .append(Component.literal(" "))
                    .append(Component.literal("[NO MAN'S LAND]")
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.RED)
                                    .withBold(true)
                                    .withHoverEvent(new HoverEvent(
                                            HoverEvent.Action.SHOW_TEXT,
                                            Lang.tr("sableprotect.info.nml_hover")))));
        }

        // Fetch is shown when the ship is outside the world border — for loaded ships using
        // the live position, for unloaded ships using the cached position (and only if we
        // have the cached metadata needed to actually run the fetch).
        final boolean fetchEligible = isMemberOrOwner && isOutsideAnyBorder(player, subLevel, data)
                && (loaded || (data.getLastKnownPlotChunk() != null && data.getLastKnownDimension() != null));

        if (fetchEligible) {
            header = header
                    .append(Component.literal("  "))
                    .append(makeButton("[Fetch from Out of Bounds]",
                            "Click to fetch this sub-level back inside the world border",
                            ClickEvent.Action.RUN_COMMAND,
                            "/sp fetch " + name));
        } else if (isMemberOrOwner) {
            // Ground replaces Fetch when not outside-border. There's no reason to ground a
            // ship that's already out of bounds — fetch is the better tool for that.
            header = header.append(Component.literal("  ")).append(formatGroundButton(player, subLevel, data, name));
        }

        // Steal button — visible to non-owners viewing a loaded ship currently in No Man's Land.
        // Final eligibility (on board, crew absent) is enforced by the command itself.
        if (!isOwner && inNoMansLand) {
            header = header
                    .append(Component.literal("  "))
                    .append(makeButton("[Steal]",
                            "Click to steal — requires being on board with the crew absent",
                            ClickEvent.Action.SUGGEST_COMMAND,
                            "/sp steal " + name));
        }
        player.displayClientMessage(header, false);

        // Location — live position when loaded, last-known cached position when unloaded.
        // Visible to owners and members. Click-copy yields "X Y Z" for use in /tp etc.
        if (isMemberOrOwner) {
            final MutableComponent locationLine = formatLocation(subLevel, data);
            if (locationLine != null) player.displayClientMessage(locationLine, false);
        }

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

        player.displayClientMessage(Lang.tr("sableprotect.info.unclaimed")
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

    private static boolean isOutsideWorldBorder(final ServerSubLevel subLevel) {
        final var border = subLevel.getLevel().getWorldBorder();
        final var pos = subLevel.logicalPose().position();
        return pos.x() < border.getMinX() || pos.x() > border.getMaxX()
                || pos.z() < border.getMinZ() || pos.z() > border.getMaxZ();
    }

    /**
     * True if the ship is outside its world border, using the live position when loaded and
     * the cached last-known position otherwise. Returns false if neither is available.
     */
    private static boolean isOutsideAnyBorder(final ServerPlayer viewer,
                                              final @Nullable ServerSubLevel subLevel,
                                              final ClaimData data) {
        if (subLevel != null) return isOutsideWorldBorder(subLevel);
        final var pos = data.getLastKnownPosition();
        final var dim = data.getLastKnownDimension();
        if (pos == null || dim == null) return false;
        final var level = viewer.getServer().getLevel(dim);
        if (level == null) return false;
        final var border = level.getWorldBorder();
        return pos.x < border.getMinX() || pos.x > border.getMaxX()
                || pos.z < border.getMinZ() || pos.z > border.getMaxZ();
    }

    /**
     * Builds the {@code [Ground]} button. Lit if no crew member is within the absence
     * radius of the ship's last-known position. Greyed-out otherwise, with a hover
     * reason. The instability warning is appended to the hover for both states because
     * it applies to the action either way.
     */
    private static MutableComponent formatGroundButton(final ServerPlayer viewer,
                                                       final @Nullable ServerSubLevel subLevel,
                                                       final ClaimData data, final String name) {
        // Resolve the position + dimension to use for the absence check.
        final net.minecraft.world.phys.Vec3 pos;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim;
        if (subLevel != null) {
            final var live = subLevel.logicalPose().position();
            pos = new net.minecraft.world.phys.Vec3(live.x(), live.y(), live.z());
            dim = subLevel.getLevel().dimension();
        } else {
            pos = data.getLastKnownPosition();
            dim = data.getLastKnownDimension();
        }

        if (pos == null || dim == null) {
            // No position to test against — render greyed with explanatory hover.
            return Component.literal("[Ground]").withStyle(style -> style
                    .withColor(ChatFormatting.DARK_GRAY)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Lang.tr("sableprotect.ground.hover_unavailable"))));
        }

        final int radius = SableProtectConfig.ABSENCE_RADIUS.get();
        final java.util.UUID blocker = CrewPresence.findCrewWithinRadius(
                viewer.getServer(), data, pos, dim, (long) radius * radius, null);

        final String warning = "\nNote: this teleport may be unstable; only use as a backup when you can't reach your ship.";

        if (blocker != null) {
            final ServerPlayer blockerPlayer = viewer.getServer().getPlayerList().getPlayer(blocker);
            final String blockerName = blockerPlayer != null
                    ? blockerPlayer.getGameProfile().getName()
                    : blocker.toString().substring(0, 8);
            return Component.literal("[Ground]").withStyle(style -> style
                    .withColor(ChatFormatting.DARK_GRAY)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Lang.tr("sableprotect.ground.hover_blocked", blockerName, radius)
                                    .append(Component.literal(warning).withStyle(ChatFormatting.GRAY)))));
        }

        return Component.literal("[Ground]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sp ground " + name))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Lang.tr("sableprotect.ground.hover_ready")
                                .append(Component.literal(warning).withStyle(ChatFormatting.GRAY)))));
    }

    /**
     * "Location: X, Y, Z" line. Live position when loaded, cached last-known position
     * when unloaded; null if the position has never been observed (legacy claim from
     * before Phase 10). Click-copies the coords as a space-separated triple.
     */
    private static @Nullable MutableComponent formatLocation(
            final @Nullable ServerSubLevel subLevel, final ClaimData data) {
        final double x;
        final double y;
        final double z;
        final boolean live;
        if (subLevel != null) {
            final var pos = subLevel.logicalPose().position();
            x = pos.x(); y = pos.y(); z = pos.z();
            live = true;
        } else {
            final var cached = data.getLastKnownPosition();
            if (cached == null) return null;
            x = cached.x; y = cached.y; z = cached.z;
            live = false;
        }
        final int xi = (int) Math.floor(x);
        final int yi = (int) Math.floor(y);
        final int zi = (int) Math.floor(z);
        final String coords = xi + ", " + yi + ", " + zi;
        final String clipboard = xi + " " + yi + " " + zi;
        final String tooltip = live
                ? "Click to copy coordinates"
                : "Last-known coordinates (sub-level not currently loaded). Click to copy.";

        final MutableComponent line = Component.literal("Location: ")
                .withStyle(ChatFormatting.GRAY);
        line.append(Component.literal(coords)
                .withStyle(style -> style
                        .withColor(live ? ChatFormatting.AQUA : ChatFormatting.GRAY)
                        .withItalic(!live)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.COPY_TO_CLIPBOARD, clipboard))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT, Component.literal(tooltip)))));
        return line;
    }

    static String resolvePlayerName(final ServerPlayer viewer, final UUID uuid) {
        final ServerPlayer target = viewer.getServer().getPlayerList().getPlayer(uuid);
        if (target != null) {
            return target.getGameProfile().getName();
        }
        return uuid.toString().substring(0, 8) + "...";
    }
}
