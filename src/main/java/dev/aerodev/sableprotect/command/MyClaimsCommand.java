package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.UUID;

public final class MyClaimsCommand {

    private MyClaimsCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("myclaims")
                .executes(ctx -> {
                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return execute(player, registry);
                });
    }

    private static int execute(final ServerPlayer player, final ClaimRegistry registry) {
        final Collection<UUID> owned = registry.getOwnedBy(player.getUUID());
        final Collection<UUID> memberOf = registry.getMemberOf(player.getUUID());

        if (owned.isEmpty() && memberOf.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.myclaims.none"), false);
            return 0;
        }

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);

        // Owned
        if (!owned.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.myclaims.owned")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
            for (final UUID subLevelId : owned) {
                final String name = registry.getNameByUuid(subLevelId);
                if (name != null) {
                    player.displayClientMessage(formatClaimEntry(name), false);
                }
            }
        }

        // Member of
        if (!memberOf.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.myclaims.member_of")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
            for (final UUID subLevelId : memberOf) {
                final String name = registry.getNameByUuid(subLevelId);
                if (name != null) {
                    player.displayClientMessage(formatClaimEntry(name), false);
                }
            }
        }

        player.displayClientMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY), false);

        return 1;
    }

    private static Component formatClaimEntry(final String name) {
        return Component.literal("  " + name)
                .withStyle(style -> style
                        .withColor(ChatFormatting.WHITE)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                "/sp info " + name))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to view info"))));
    }
}
