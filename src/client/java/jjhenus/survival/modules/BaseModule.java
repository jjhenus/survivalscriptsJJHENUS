package jjhenus.survival.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public abstract class BaseModule {
    protected int delayTimer = 0;
    protected int watchdogTicks = 0;
    protected boolean stopped = false;

    public abstract void onTick(MinecraftClient client);
    public abstract void renderHud(DrawContext context, MinecraftClient client, int x, int y);
    public abstract void stop(MinecraftClient client, String reason);

    public boolean isStopped() { return stopped; }

    /**
     * Basic survival safety check. Returns true if the player is in danger.
     * Modules can override for custom behavior.
     */
    protected boolean isPlayerInDanger(MinecraftClient client) {
        if (client.player == null) return true;
        // Stop if health drops below 4 hearts (8 HP)
        if (client.player.getHealth() <= 8.0f) return true;
        // Stop if player is dead
        if (client.player.isDead()) return true;
        return false;
    }
}