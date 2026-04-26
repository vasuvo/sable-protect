package dev.aerodev.sableprotect.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Player-name resolution helpers used by commands that accept offline players.
 *
 * <p>The lookup is intentionally restricted to <em>known</em> players — those currently
 * online or cached in the server's {@link net.minecraft.server.players.GameProfileCache}
 * (i.e. anyone who has logged in at least once). It will <strong>not</strong> hit the
 * Mojang API for unknown usernames, so an admin can't silently grant rights to a
 * randomly-typed username that nobody on the server has ever used.
 */
public final class Players {

    private Players() {}

    /**
     * Resolve a player name to a {@link GameProfile} if (and only if) that player is
     * currently online or in the server's profile cache. Returns null otherwise — the
     * caller should treat that as "unknown player" and produce a user-facing error.
     */
    public static @Nullable GameProfile resolveKnown(final MinecraftServer server, final String name) {
        if (name == null || name.isEmpty()) return null;
        final ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getGameProfile();
        try {
            return server.getProfileCache() == null ? null : server.getProfileCache().get(name).orElse(null);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /** Resolve a UUID to a display name via online players first, then profile cache. */
    public static String resolveDisplayName(final MinecraftServer server, final java.util.UUID uuid) {
        final ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        try {
            if (server.getProfileCache() != null) {
                final var cached = server.getProfileCache().get(uuid);
                if (cached.isPresent()) return cached.get().getName();
            }
        } catch (final Throwable ignored) {}
        return uuid.toString().substring(0, 8) + "...";
    }
}
