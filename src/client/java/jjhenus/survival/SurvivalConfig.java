package jjhenus.survival;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

public class SurvivalConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("survival_scripts.json");
    private static SurvivalConfig instance;

    public int placeDelay = 3;
    public int breakDelay = 3;
    public int repairThrowDelay = 4;
    public double adaptiveDelayPerPing = 0.5;

    public static SurvivalConfig get() {
        if (instance == null) instance = new SurvivalConfig();
        return instance;
    }

    public static void load() { /* JSON loading logic */ }
}