package jjhenus.clayfarmer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class ClayMenuScreen extends Screen {

    public ClayMenuScreen() {
        super(Text.literal("ClayBot Control Panel"));
    }

    @Override
    protected void init() {
        int buttonWidth = 90;
        int buttonHeight = 20;

        // Setup columns
        int col1X = this.width / 2 - 150; // Clay Farmer
        int col2X = this.width / 2 - 45;  // Clay Trader
        int col3X = this.width / 2 + 60;  // Trade Roller

        int startY = 40;
        int spacing = 25;

        // ─── Column 1: Clay Farmer ───
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Farmer"), b -> runCommand("claybot start"))
                .dimensions(col1X, startY, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Stop Farmer"), b -> runCommand("claybot stop"))
                .dimensions(col1X, startY + spacing, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Stats"), b -> runCommand("claybot stats"))
                .dimensions(col1X, startY + spacing * 2, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset Stats"), b -> runCommand("claybot reset"))
                .dimensions(col1X, startY + spacing * 3, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reload Config"), b -> runCommand("claybot reload"))
                .dimensions(col1X, startY + spacing * 4, buttonWidth, buttonHeight).build());

        // ─── Column 2: Clay Trader ───
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Trader"), b -> runCommand("claytrade start"))
                .dimensions(col2X, startY, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Stop Trader"), b -> runCommand("claytrade stop"))
                .dimensions(col2X, startY + spacing, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Scan Villagers"), b -> runCommand("claytrade scan"))
                .dimensions(col2X, startY + spacing * 2, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Stats"), b -> runCommand("claytrade stats"))
                .dimensions(col2X, startY + spacing * 3, buttonWidth, buttonHeight).build());

        // ─── Column 3: Trade Roller ───
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Roller"), b -> runCommand("traderoller start"))
                .dimensions(col3X, startY, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Stop Roller"), b -> runCommand("traderoller stop"))
                .dimensions(col3X, startY + spacing, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Set Pos"), b -> runCommand("traderoller pos"))
                .dimensions(col3X, startY + spacing * 2, buttonWidth, buttonHeight).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Set Tool"), b -> runCommand("traderoller tool"))
                .dimensions(col3X, startY + spacing * 3, buttonWidth, buttonHeight).build());
    }

    private void runCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            // Updated method name for newer Minecraft versions
            client.getNetworkHandler().sendChatCommand(command);
        }
        this.close(); // Automatically close the menu after clicking a button
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Render the dark transparent background
        this.renderBackground(context, mouseX, mouseY, delta);

        // Main Title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);

        // Column Headers
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00a76Clay Farmer"), this.width / 2 - 135, 25, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00a72Clay Trader"), this.width / 2 - 30, 25, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00a7dTrade Roller"), this.width / 2 + 75, 25, 0xFFFFFF);

        super.render(context, mouseX, mouseY, delta);
    }
}