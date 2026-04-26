package dev.aerodev.sableprotect.audit;

import dev.aerodev.sableprotect.SableProtectMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Append-only audit log of claim lifecycle events. Written as plain text, one event per
 * line, to {@code <server-root>/logs/sableprotect-audit.log}. Opens on
 * {@code ServerStartedEvent} and closes on {@code ServerStoppingEvent}; all writes go
 * through {@code synchronized} blocks for safety, though all callers happen on the
 * server thread in practice.
 *
 * <p>Format example:
 * <pre>
 * 2026-04-26T08:30:15Z CREATE     name="MyShip"  uuid=abc12345-...  actor=Vasuvo(uuid)  context=command
 * 2026-04-26T08:31:42Z TRANSFER   name="MyShip"  uuid=abc12345-...  from=Vasuvo(uuid) to=Bob(uuid)  context=changeowner
 * 2026-04-26T10:15:03Z DELETE     name="MyShip"  uuid=abc12345-...  actor=Carl(uuid)   context=unclaim
 * 2026-04-26T11:00:55Z DELETE     name="OldShip" uuid=def...        actor=&lt;system&gt;    context=destroyed
 * </pre>
 */
public final class AuditLog {

    public static final String FILE_NAME = "sableprotect-audit.log";

    private static final Object LOCK = new Object();
    private static @Nullable BufferedWriter writer = null;

    private AuditLog() {}

    public static void open(final MinecraftServer server) {
        synchronized (LOCK) {
            if (writer != null) return;
            try {
                final Path logDir = server.getServerDirectory().resolve("logs");
                Files.createDirectories(logDir);
                final Path logFile = logDir.resolve(FILE_NAME);
                writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                SableProtectMod.LOGGER.info("[sable-protect] Audit log opened at {}", logFile);
            } catch (final IOException e) {
                SableProtectMod.LOGGER.error("[sable-protect] Failed to open audit log: {}", e.getMessage());
                writer = null;
            }
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writer.flush();
                writer.close();
            } catch (final IOException e) {
                SableProtectMod.LOGGER.warn("[sable-protect] Error closing audit log: {}", e.getMessage());
            }
            writer = null;
        }
    }

    /** A new claim was created — `/sp claim`, `/sp claimuuid`, or split inheritance. */
    public static void logCreate(final @Nullable MinecraftServer server, final String claimName,
                                 final UUID subLevelId, final @Nullable ServerPlayer actor,
                                 final String context) {
        write(String.format("CREATE     name=\"%s\"  uuid=%s  actor=%s  context=%s",
                claimName, subLevelId, actorTag(actor), context));
    }

    /** Ownership transferred — {@code /sp edit changeowner} or {@code /sp steal}. */
    public static void logTransfer(final MinecraftServer server, final String claimName,
                                   final UUID subLevelId, final UUID fromUuid, final UUID toUuid,
                                   final String context) {
        write(String.format("TRANSFER   name=\"%s\"  uuid=%s  from=%s  to=%s  context=%s",
                claimName, subLevelId,
                playerTag(server, fromUuid),
                playerTag(server, toUuid),
                context));
    }

    /** A claim was deleted — {@code /sp unclaim} or sub-level destruction. */
    public static void logDelete(final @Nullable MinecraftServer server, final String claimName,
                                 final UUID subLevelId, final @Nullable ServerPlayer actor,
                                 final String context) {
        write(String.format("DELETE     name=\"%s\"  uuid=%s  actor=%s  context=%s",
                claimName, subLevelId, actorTag(actor), context));
    }

    private static String actorTag(final @Nullable ServerPlayer actor) {
        if (actor == null) return "<system>";
        return actor.getGameProfile().getName() + "(" + actor.getUUID() + ")";
    }

    /** Resolve UUID to "name(uuid)" using online players first, then the profile cache. */
    private static String playerTag(final MinecraftServer server, final UUID uuid) {
        final ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName() + "(" + uuid + ")";
        }
        try {
            final var cached = server.getProfileCache();
            if (cached != null) {
                return cached.get(uuid)
                        .map(p -> p.getName() + "(" + uuid + ")")
                        .orElse(uuid.toString());
            }
        } catch (final Throwable ignored) {}
        return uuid.toString();
    }

    private static void write(final String body) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writer.write(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
                writer.write(' ');
                writer.write(body);
                writer.newLine();
                writer.flush();
            } catch (final IOException e) {
                SableProtectMod.LOGGER.warn("[sable-protect] Failed to write audit log entry: {}", e.getMessage());
            }
        }
    }
}
