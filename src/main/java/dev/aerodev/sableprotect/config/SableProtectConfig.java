package dev.aerodev.sableprotect.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SableProtectConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MINIMUM_CLAIM_MASS;
    public static final ModConfigSpec.IntValue FREEZE_DURATION_SECONDS;
    public static final ModConfigSpec.IntValue FETCH_BORDER_INSET;
    public static final ModConfigSpec.IntValue ABSENCE_RADIUS;
    public static final ModConfigSpec.IntValue ADMIN_BYPASS_PERMISSION_LEVEL;

    public static final ModConfigSpec.BooleanValue ENABLE_CONTRAPTION_BREAKER_PROTECTION;
    public static final ModConfigSpec.BooleanValue ALLOW_EXTERNAL_ANCHOR_BREAKING;

    public static final ModConfigSpec.BooleanValue NO_MANS_LAND_ENABLED;
    public static final ModConfigSpec.IntValue NO_MANS_LAND_MIN_X;
    public static final ModConfigSpec.IntValue NO_MANS_LAND_MAX_X;
    public static final ModConfigSpec.IntValue NO_MANS_LAND_MIN_Z;
    public static final ModConfigSpec.IntValue NO_MANS_LAND_MAX_Z;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        MINIMUM_CLAIM_MASS = builder
                .comment("Minimum mass for a sub-level to be claimable, and to inherit claim data on a split.")
                .defineInRange("minimumClaimMass", 4, 1, 1_000_000);

        FREEZE_DURATION_SECONDS = builder
                .comment("How long, in seconds, the physics of a sub-level are frozen after",
                         "any teleport command (/sp fetch, /sp ground).")
                .defineInRange("freezeDurationSeconds", 60, 1, 3600);

        FETCH_BORDER_INSET = builder
                .comment("How far, in blocks, inside the world border to place a fetched sub-level.")
                .defineInRange("fetchBorderInset", 50, 0, 10_000);

        ABSENCE_RADIUS = builder
                .comment("Radius (in blocks) around a sub-level's center used to test whether the",
                         "crew is absent. Used by /sp steal (excluding the issuer) and /sp ground",
                         "(including the issuer). Players outside this radius — or offline, or in",
                         "another dimension — are treated as absent.")
                .defineInRange("absenceRadius", 100, 1, 10_000);

        ADMIN_BYPASS_PERMISSION_LEVEL = builder
                .comment("Vanilla permission level required to bypass all claim protection.",
                         "Vanilla permission levels: 0 = none, 2 = gamemasters, 3 = admins, 4 = full ops.",
                         "Set to 5 (above max) to disable the admin bypass entirely.")
                .defineInRange("adminBypassPermissionLevel", 4, 0, 5);

        builder.push("contraptionBreakers");
        builder.comment("Protection against contraption-mounted block breakers that bypass the",
                        "vanilla BlockEvent.BreakEvent path. Currently covers Create's mechanical",
                        "drill and Create Simulated's rock cutting wheel. Saws, ploughs, rollers,",
                        "and harvesters are intentionally left unprotected — their use cases",
                        "(on-ship farms, surface clearing) are valuable enough that the grief",
                        "risk doesn't justify blocking them.");

        ENABLE_CONTRAPTION_BREAKER_PROTECTION = builder
                .comment("Master toggle for drill / rock-cutting-wheel protection. When enabled,",
                         "a breaker can only damage a claimed sub-level if its host contraption",
                         "is anchored on the same sub-level, or its host is owned by an owner or",
                         "member of the target's claim.")
                .define("enabled", true);

        ALLOW_EXTERNAL_ANCHOR_BREAKING = builder
                .comment("Whether breakers anchored in the open world (no host sub-level) may",
                         "still damage claimed sub-levels. Off by default since this is the",
                         "'park a drill ship next to someone's claim' attack vector. Turn on if",
                         "you have legitimate stationary mining setups that target ships.")
                .define("allowExternalAnchorBreaking", false);

        builder.pop();

        builder.push("noMansLand");
        builder.comment("A rectangular XZ region in which all claim protections are suspended.",
                        "Inside this region, players can break, interact with, and disassemble any sub-level",
                        "regardless of ownership. Owners can also be replaced via /sp steal when no live",
                        "owner or member is within absenceRadius blocks of the ship.");

        NO_MANS_LAND_ENABLED = builder
                .comment("Whether the No Man's Land region is active. Default off so existing servers",
                         "don't suddenly gain a permission-free zone.")
                .define("enabled", false);

        NO_MANS_LAND_MIN_X = builder
                .comment("Minimum X (inclusive) of the No Man's Land rectangle.")
                .defineInRange("minX", 1000, Integer.MIN_VALUE, Integer.MAX_VALUE);
        NO_MANS_LAND_MAX_X = builder
                .comment("Maximum X (inclusive) of the No Man's Land rectangle.")
                .defineInRange("maxX", 5000, Integer.MIN_VALUE, Integer.MAX_VALUE);
        NO_MANS_LAND_MIN_Z = builder
                .comment("Minimum Z (inclusive) of the No Man's Land rectangle.")
                .defineInRange("minZ", 1000, Integer.MIN_VALUE, Integer.MAX_VALUE);
        NO_MANS_LAND_MAX_Z = builder
                .comment("Maximum Z (inclusive) of the No Man's Land rectangle.")
                .defineInRange("maxZ", 5000, Integer.MIN_VALUE, Integer.MAX_VALUE);

        builder.pop();

        SPEC = builder.build();
    }

    private SableProtectConfig() {}
}
