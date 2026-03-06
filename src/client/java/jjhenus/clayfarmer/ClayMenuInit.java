package jjhenus.clayfarmer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

public class ClayMenuInit implements ClientModInitializer {

    private static KeyBinding menuKeyBind;

    @Override
    public void onInitializeClient() {
        // Register the "M" key to open the menu, using the existing category from ClayTraderBot
        menuKeyBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.clayfarmer.menu",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_M,
                ClayTraderBot.KEYBIND_CATEGORY // <-- Changed this line!
        ));

        // Listen for the key press every tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKeyBind.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new ClayMenuScreen());
                }
            }
        });
    }
}