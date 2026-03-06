package jjhenus.survival.gui;

import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.modules.FarmerModule;
import jjhenus.survival.modules.TraderModule;
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
        int bw = 100, bh = 20, startY = 40, spacing = 25;
        int cx = this.width / 2;

        // Farmer
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Farmer"), b -> {
            MinecraftClient client = MinecraftClient.getInstance();
            FarmerModule farmer = new FarmerModule();
            if (farmer.preflight(client)) {
                SurvivalScriptsClient.setActiveModule(farmer);
            }
            this.close();
        }).dimensions(cx - 155, startY, bw, bh).build());

        // Trader
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Trader"), b -> {
            SurvivalScriptsClient.setActiveModule(new TraderModule());
            this.close();
        }).dimensions(cx - 50, startY, bw, bh).build());

        // Roller — uses shared config
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Roller"), b -> {
            if (SurvivalScriptsClient.ROLLER_CONFIG.getWorkbenchPos() == null) {
                if (MinecraftClient.getInstance().player != null)
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.literal("§cUse '/traderoller pos' to set position first!"), false);
            } else {
                SurvivalScriptsClient.setActiveModule(SurvivalScriptsClient.createRollerFromConfig());
            }
            this.close();
        }).dimensions(cx + 55, startY, bw, bh).build());

        // Stop All
        this.addDrawableChild(ButtonWidget.builder(Text.literal("§c§lSTOP ALL"), b -> {
            SurvivalScriptsClient.setActiveModule(null);
            this.close();
        }).dimensions(cx - 50, startY + (spacing * 5), bw, bh).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);

        // Show roller config status
        String rollerStatus = SurvivalScriptsClient.ROLLER_CONFIG.getWorkbenchPos() != null
                ? "§aPos: " + SurvivalScriptsClient.ROLLER_CONFIG.getWorkbenchPos().toShortString()
                : "§cPos: Not set";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Roller " + rollerStatus),
                this.width / 2, this.height - 20, 0xFFFFFF);
    }
}