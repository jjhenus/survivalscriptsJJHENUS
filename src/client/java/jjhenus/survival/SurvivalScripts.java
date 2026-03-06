package jjhenus.survival;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SurvivalScripts implements ModInitializer {
    public static final String MOD_ID = "survival_scripts";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Survival Scripts initialized.");
    }
}