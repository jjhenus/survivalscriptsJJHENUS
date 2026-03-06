package jjhenus.survival;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class SurvivalConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("survival_scripts.json");
    private static SurvivalConfig INSTANCE;

    // ── Timing (ticks) ──────────────────────────────────────────────
    public int placeDelay = 3;
    public int breakDelay = 3;
    public int repairThrowDelay = 4;
    public int shulkerOpenDelay = 5;
    public int shulkerTransferDelay = 15;
    public int shulkerBreakDelay = 5;
    public double adaptiveDelayPerPing = 0.5;

    // ── Durability ──────────────────────────────────────────────────
    public int durabilityRepairThreshold = 50;
    public int durabilityRepairedTarget = 200;

    // ── Scanning ────────────────────────────────────────────────────
    public int shulkerSearchRadius = 4;
    public boolean acceptAnyShulkerColor = false;

    // ── Trader ───────────────────────────────────────────────────────
    public int searchRadius = 64;
    public int approachDistance = 3;
    public int tradeDelay = 3;
    public int pathingTimeout = 600;
    public int maxInteractRetries = 20;
    public int maxOpenRetries = 10;
    public int emeraldDepositThreshold = 128;

    // ── Watchdog ────────────────────────────────────────────────────
    public int watchdogTimeout = 400;
    public int stateTimeoutTicks = 200;

    // ── HUD ─────────────────────────────────────────────────────────
    public int hudX = 10;
    public int hudY = 10;
    public int hudColor = 0xFFFFFF;
    public boolean hudShadow = true;

    // ── Alerts ──────────────────────────────────────────────────────
    public boolean alertSoundOnStop = true;
    public boolean alertChatOnStop = true;

    // ── Misc ────────────────────────────────────────────────────────
    public boolean recoverShulkerDrops = true;
    public int maxShulkerQueue = 8;

    // ── Singleton ────────────────────────────────────────────────────

    public static SurvivalConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(reader, SurvivalConfig.class);
                if (INSTANCE == null) INSTANCE = new SurvivalConfig();
            } catch (Exception e) {
                System.err.println("[SurvivalScripts] Failed to load config: " + e.getMessage());
                INSTANCE = new SurvivalConfig();
            }
        } else {
            INSTANCE = new SurvivalConfig();
            save();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(INSTANCE != null ? INSTANCE : new SurvivalConfig(), writer);
            }
        } catch (Exception e) {
            System.err.println("[SurvivalScripts] Failed to save config: " + e.getMessage());
        }
    }

    public static void reload() {
        INSTANCE = null;
        load();
    }
}