package dev.aerodev.sableprotect.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SableProtectConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MINIMUM_CLAIM_MASS;
    public static final ModConfigSpec.IntValue FETCH_FREEZE_DURATION_SECONDS;
    public static final ModConfigSpec.IntValue FETCH_BORDER_INSET;
    public static final ModConfigSpec.IntValue ADMIN_BYPASS_PERMISSION_LEVEL;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        MINIMUM_CLAIM_MASS = builder
                .comment("Minimum mass for a sub-level to be claimable, and to inherit claim data on a split.")
                .defineInRange("minimumClaimMass", 4, 1, 1_000_000);

        FETCH_FREEZE_DURATION_SECONDS = builder
                .comment("How long, in seconds, the physics of a sub-level are frozen after /sp fetch.")
                .defineInRange("fetchFreezeDurationSeconds", 60, 1, 3600);

        FETCH_BORDER_INSET = builder
                .comment("How far, in blocks, inside the world border to place a fetched sub-level.")
                .defineInRange("fetchBorderInset", 50, 0, 10_000);

        ADMIN_BYPASS_PERMISSION_LEVEL = builder
                .comment("Vanilla permission level required to bypass all claim protection.",
                         "Vanilla permission levels: 0 = none, 2 = gamemasters, 3 = admins, 4 = full ops.",
                         "Set to 5 (above max) to disable the admin bypass entirely.")
                .defineInRange("adminBypassPermissionLevel", 4, 0, 5);

        SPEC = builder.build();
    }

    private SableProtectConfig() {}
}
