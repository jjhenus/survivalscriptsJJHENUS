package jjhenus.survival.gui;

import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.modules.FarmerModule;
import jjhenus.survival.modules.TraderModule;
import jjhenus.survival.modules.RollerModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class SurvivalMenuScreen extends Screen {
    public SurvivalMenuScreen() {
        super(Text.literal("Survival Scripts Control Panel"));
    }

    @Override
    protected void init() {
        int buttonWidth = 100;
        int buttonHeight = 20;
        int startY = 40;
        int spacing = 25;
        int centerX = this.width / 2;

        // Farmer Column
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Farmer"), b -> {
            SurvivalScriptsClient.setActiveModule(new FarmerModule());
            this.close();
        }).dimensions(centerX - 155, startY, buttonWidth, buttonHeight).build());

        // Trader Column
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Trader"), b -> {
            SurvivalScriptsClient.setActiveModule(new TraderModule());
            this.close();
        }).dimensions(centerX - 50, startY, buttonWidth, buttonHeight).build());

        // Roller Column
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Roller"), b -> {
            SurvivalScriptsClient.setActiveModule(new RollerModule());
            this.close();
        }).dimensions(centerX + 55, startY, buttonWidth, buttonHeight).build());

        // Global Stop Button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("STOP ALL"), b -> {
            SurvivalScriptsClient.setActiveModule(null);
            this.close();
        }).dimensions(centerX - 50, startY + (spacing * 5), buttonWidth, buttonHeight).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
}