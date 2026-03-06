package jjhenus.survival.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public abstract class BaseModule {
    protected int delayTimer = 0;
    protected int watchdogTicks = 0;

    public abstract void onTick(MinecraftClient client);
    public abstract void renderHud(DrawContext context, MinecraftClient client, int x, int y);
    public abstract void stop(MinecraftClient client, String reason);
}