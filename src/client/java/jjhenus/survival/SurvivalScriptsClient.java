package jjhenus.survival;

import jjhenus.survival.gui.SurvivalMenuScreen;
import jjhenus.survival.modules.BaseModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class SurvivalScriptsClient implements ClientModInitializer {
    private static BaseModule activeModule = null;
    public static final ShulkerIndex SHULKER_INDEX = new ShulkerIndex();
    private static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        SurvivalConfig.load();

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.survival.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.survival.main"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (menuKey.wasPressed() && client.currentScreen == null) {
                client.setScreen(new SurvivalMenuScreen());
            }
            if (activeModule != null) activeModule.onTick(client);
        });

        HudRenderCallback.EVENT.register((context, delta) -> {
            if (activeModule != null) activeModule.renderHud(context, client, 10, 10);
        });
    }

    public static void setActiveModule(BaseModule module) {
        activeModule = module;
    }
}