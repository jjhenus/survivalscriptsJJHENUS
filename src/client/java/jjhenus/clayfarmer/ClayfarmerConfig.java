package jjhenus.clayfarmer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Externalized configuration for ClayBot.
 * Saved as JSON in .minecraft/config/clayfarmer.json
 */
public class ClayfarmerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("clayfarmer.json");

    private static ClayfarmerConfig INSTANCE;

    // ── Timing (ticks) ──────────────────────────────────────────────────
    /** Delay after placing a clay block before we start breaking. */
    public int placeDelay = 3;

    /** Delay after a block is broken before we place the next one. */
    public int breakDelay = 3;

    /** Delay between XP bottle throws while repairing. */
    public int repairThrowDelay = 4;

    /** Delay before opening a shulker box (let the client settle). */
    public int shulkerOpenDelay = 5;

    /** Delay after opening the shulker screen before transferring items. */
    public int shulkerTransferDelay = 15;

    /** Delay after breaking a shulker before resuming. */
    public int shulkerBreakDelay = 5;

    /** Extra ticks added to all delays per 50ms of server ping. 0 = disabled. */
    public double adaptiveDelayPerPing = 0.5;

    // ── Durability ──────────────────────────────────────────────────────
    /** Remaining durability at which we stop breaking and start repairing. */
    public int durabilityRepairThreshold = 50;

    /** Remaining durability at which we consider the shovel repaired. */
    public int durabilityRepairedTarget = 200;

    // ── Scanning ────────────────────────────────────────────────────────
    /** Block search radius around the player for shulker boxes. */
    public int shulkerSearchRadius = 4;

    /** Accept any color shulker box, not just light gray. */
    public boolean acceptAnyShulkerColor = false;

    // ── Watchdog ────────────────────────────────────────────────────────
    /** Max ticks a single state can run without progress before force-resetting. */
    public int stateTimeoutTicks = 200; // ~10 seconds

    // ── HUD ─────────────────────────────────────────────────────────────
    public int hudX = 10;
    public int hudY = 10;
    public int hudColor = 0xFFFFFF;
    public boolean hudShadow = true;

    // ── Alerts ──────────────────────────────────────────────────────────
    /** Play a ding sound when the bot stops for any reason. */
    public boolean alertSoundOnStop = true;

    /** Send a chat message when the bot stops. */
    public boolean alertChatOnStop = true;

    // ── Misc ────────────────────────────────────────────────────────────
    /** Try to pick up dropped shulker box items after breaking them. */
    public boolean recoverShulkerDrops = true;

    /** Maximum number of shulker boxes to queue for multi-shulker cycling. */
    public int maxShulkerQueue = 8;

    // ────────────────────────────────────────────────────────────────────

    public static ClayfarmerConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(reader, ClayfarmerConfig.class);
                if (INSTANCE == null) INSTANCE = new ClayfarmerConfig();
            } catch (Exception e) {
                System.err.println("[ClayBot] Failed to load config, using defaults: " + e.getMessage());
                INSTANCE = new ClayfarmerConfig();
            }
        } else {
            INSTANCE = new ClayfarmerConfig();
            save(); // write defaults
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(INSTANCE != null ? INSTANCE : new ClayfarmerConfig(), writer);
            }
        } catch (Exception e) {
            System.err.println("[ClayBot] Failed to save config: " + e.getMessage());
        }
    }

    /** Reload from disk (useful for /claybot reload). */
    public static void reload() {
        INSTANCE = null;
        load();
    }
}