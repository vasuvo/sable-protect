package dev.aerodev.sableprotect.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aerodev.sableprotect.SableProtectMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side string lookup. Sable Protect runs server-only, so the client never receives
 * our {@code en_us.json} via the standard resource pipeline — translatable components
 * arrive as raw keys. This helper bundles the JSON inside the jar, loads it at class-init,
 * and produces pre-resolved {@link Component}s.
 *
 * <p>Intentionally simple: only English strings, only {@code %s}-style substitution via
 * {@link String#format}. {@link Component} arguments lose their styling (rendered as plain
 * text); if a particular message ever needs colored substitutions, build the component
 * by hand at the call site.
 */
public final class Lang {

    private static final String LANG_PATH = "/assets/sableprotect/lang/en_us.json";
    private static final Map<String, String> TABLE = new HashMap<>();

    static { load(); }

    private Lang() {}

    private static void load() {
        try (InputStream in = Lang.class.getResourceAsStream(LANG_PATH)) {
            if (in == null) {
                SableProtectMod.LOGGER.error("[sable-protect] Language resource missing: {}", LANG_PATH);
                return;
            }
            final JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (final Map.Entry<String, ?> entry : root.entrySet()) {
                TABLE.put(entry.getKey(), root.get(entry.getKey()).getAsString());
            }
        } catch (final IOException e) {
            SableProtectMod.LOGGER.error("[sable-protect] Failed to load language file", e);
        }
    }

    /** Force a reload of the table from the bundled resource — used by {@code /sp reload}. */
    public static void reload() {
        TABLE.clear();
        load();
    }

    /** Look up a key, substitute {@code %s} args, return as a plain literal component. */
    public static MutableComponent tr(final String key, final Object... args) {
        final String template = TABLE.getOrDefault(key, key);
        if (args.length == 0) return Component.literal(template);

        final Object[] flat = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            flat[i] = args[i] instanceof Component c ? c.getString() : args[i];
        }
        try {
            return Component.literal(String.format(template, flat));
        } catch (final Exception ignored) {
            return Component.literal(template);
        }
    }
}
