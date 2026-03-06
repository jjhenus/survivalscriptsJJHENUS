package jjhenus.survival;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import jjhenus.survival.gui.SurvivalMenuScreen;
import jjhenus.survival.modules.BaseModule;
import jjhenus.survival.modules.FarmerModule;
import jjhenus.survival.modules.TraderModule;
import jjhenus.survival.modules.RollerModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class SurvivalScriptsClient implements ClientModInitializer {
    private static BaseModule activeModule = null;
    private static boolean isStopping = false; // recursion guard for stop()
    private static boolean mouseUngrabbed = false;
    public static final ShulkerIndex SHULKER_INDEX = new ShulkerIndex();
    private static KeyBinding menuKey;
    private static KeyBinding stopKey;

    // Shared roller config that persists between commands
    public static final RollerModule ROLLER_CONFIG = new RollerModule();

    @Override
    public void onInitializeClient() {
        SurvivalConfig.load();

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.survival.menu", GLFW.GLFW_KEY_M, KeyBinding.Category.MISC));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.survival.stop", GLFW.GLFW_KEY_K, KeyBinding.Category.MISC));

        // ── ALL Commands in ONE callback ─────────────────────────────
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // /claybot
            dispatcher.register(ClientCommandManager.literal("claybot")
                    .then(ClientCommandManager.literal("start").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        FarmerModule farmer = new FarmerModule();
                        if (!farmer.preflight(client)) return 0;
                        setActiveModule(farmer);
                        mouseUngrabbed = true;
                        chat(client, "§a§lFarmer started! §rIndexing shulkers...");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stop").executes(ctx -> { stopActive("Manual stop."); return 1; }))
                    .then(ClientCommandManager.literal("reload").executes(ctx -> {
                        SurvivalConfig.reload(); chat(MinecraftClient.getInstance(), "§bConfig reloaded."); return 1;
                    }))
                    .then(ClientCommandManager.literal("ungrab").executes(ctx -> {
                        mouseUngrabbed = true;
                        MinecraftClient.getInstance().mouse.unlockCursor();
                        chat(MinecraftClient.getInstance(), "§bMouse released.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("grab").executes(ctx -> {
                        mouseUngrabbed = false;
                        MinecraftClient.getInstance().mouse.lockCursor();
                        chat(MinecraftClient.getInstance(), "§bMouse locked.");
                        return 1;
                    }))
            );

            // /claytrade
            dispatcher.register(ClientCommandManager.literal("claytrade")
                    .then(ClientCommandManager.literal("start").executes(ctx -> {
                        setActiveModule(new TraderModule());
                        mouseUngrabbed = true;
                        chat(MinecraftClient.getInstance(), "§a§lTrader started! §rPress §fK§a to stop.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stop").executes(ctx -> { stopActive("Manual stop."); return 1; }))
                    .then(ClientCommandManager.literal("scan").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        SHULKER_INDEX.scanNearbyShulkers(client.player, SurvivalConfig.get().shulkerSearchRadius, false);
                        chat(client, "§7Shulkers: §f" + SHULKER_INDEX.getTotalCount()
                                + " §7Indexed: §f" + SHULKER_INDEX.getIndexedCount()
                                + " §7Clay: " + (SHULKER_INDEX.hasClayBallSources() ? "§aYes" : "§cNo")
                                + " §7Em: " + (SHULKER_INDEX.hasEmeraldDepositTargets() ? "§aYes" : "§cNo"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("reset").executes(ctx -> {
                        SHULKER_INDEX.clear(); chat(MinecraftClient.getInstance(), "§bIndex reset."); return 1;
                    }))
            );

            // /traderoller
            dispatcher.register(ClientCommandManager.literal("traderoller")

                    .then(ClientCommandManager.literal("pos").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.crosshairTarget instanceof BlockHitResult hit) {
                            BlockPos pos = hit.getBlockPos().offset(hit.getSide());
                            ROLLER_CONFIG.setWorkbenchPos(pos);
                            chat(client, "§aWorkbench target set to: §f" + pos.toShortString());
                        } else {
                            chat(client, "§cLook at the floor block where the workbench should go.");
                        }
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("tool").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            if (client.player.getMainHandStack().isEmpty()) {
                                ROLLER_CONFIG.setBreakingTool(null);
                                chat(client, "§eBreaking tool: §aAuto (Best Tool)");
                            } else {
                                Item held = client.player.getMainHandStack().getItem();
                                ROLLER_CONFIG.setBreakingTool(held);
                                chat(client, "§eBreaking tool: §f" + held.getName().getString());
                            }
                        }
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("buy")
                            .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                    .then(ClientCommandManager.argument("maxPrice", IntegerArgumentType.integer(1, 64))
                                            .executes(ctx -> {
                                                Item item = RollerModule.parseItem(StringArgumentType.getString(ctx, "item"));
                                                int price = IntegerArgumentType.getInteger(ctx, "maxPrice");
                                                ROLLER_CONFIG.setTargetBuy(item, price);
                                                chat(MinecraftClient.getInstance(), "§bTarget Buy: §f" + price + "x " + item.getName().getString());
                                                return 1;
                                            }))))

                    .then(ClientCommandManager.literal("sell")
                            .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                    .executes(ctx -> {
                                        Item item = RollerModule.parseItem(StringArgumentType.getString(ctx, "item"));
                                        ROLLER_CONFIG.setTargetSell(item);
                                        chat(MinecraftClient.getInstance(), "§aTarget Sell: §f" + item.getName().getString());
                                        return 1;
                                    })))

                    .then(ClientCommandManager.literal("block")
                            .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                    .executes(ctx -> {
                                        Item item = RollerModule.parseItem(StringArgumentType.getString(ctx, "item"));
                                        ROLLER_CONFIG.setTargetWorkbench(item);
                                        chat(MinecraftClient.getInstance(), "§eWorkbench block: §f" + item.getName().getString());
                                        return 1;
                                    })))

                    .then(ClientCommandManager.literal("enchant")
                            .then(ClientCommandManager.argument("enchantment", StringArgumentType.string())
                                    .then(ClientCommandManager.argument("minLevel", IntegerArgumentType.integer(1, 10))
                                            .then(ClientCommandManager.argument("maxPrice", IntegerArgumentType.integer(1, 64))
                                                    .executes(ctx -> {
                                                        String enchName = StringArgumentType.getString(ctx, "enchantment");
                                                        int minLevel = IntegerArgumentType.getInteger(ctx, "minLevel");
                                                        int maxPrice = IntegerArgumentType.getInteger(ctx, "maxPrice");
                                                        Identifier enchId = enchName.contains(":")
                                                                ? Identifier.of(enchName.split(":")[0], enchName.split(":")[1])
                                                                : Identifier.ofVanilla(enchName);
                                                        ROLLER_CONFIG.setEnchantmentTarget(enchId, minLevel, maxPrice);
                                                        chat(MinecraftClient.getInstance(), "§dTargeting: §f"
                                                                + enchId.getPath() + " Lvl " + minLevel
                                                                + "+ for max §a" + maxPrice + " Emeralds");
                                                        return 1;
                                                    })))))

                    .then(ClientCommandManager.literal("start").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (ROLLER_CONFIG.getWorkbenchPos() == null) {
                            chat(client, "§cUse '/traderoller pos' first!");
                            return 0;
                        }
                        setActiveModule(createRollerFromConfig());
                        chat(client, "§a§lTrade Roller started! §rPress §fK§a to stop.");
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("stop").executes(ctx -> { stopActive("Manual stop."); return 1; }))

                    .then(ClientCommandManager.literal("status").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        chat(client, "§6§l== Roller Config ==");
                        chat(client, "§f Pos: §7" + (ROLLER_CONFIG.getWorkbenchPos() != null
                                ? ROLLER_CONFIG.getWorkbenchPos().toShortString() : "§cNot set"));
                        chat(client, "§f Block: §7" + ROLLER_CONFIG.targetWorkbench.getName().getString());
                        if (ROLLER_CONFIG.isLookingForEnchantment && ROLLER_CONFIG.targetEnchantmentId != null) {
                            chat(client, "§f Mode: §dEnchantment §f" + ROLLER_CONFIG.targetEnchantmentId.getPath()
                                    + " Lvl " + ROLLER_CONFIG.targetEnchantmentMinLevel
                                    + "+ (≤" + ROLLER_CONFIG.targetBuyMaxPrice + " Em)");
                        } else {
                            chat(client, "§f Buy: §7" + ROLLER_CONFIG.targetBuyMaxPrice + "x " + ROLLER_CONFIG.targetBuyItem.getName().getString());
                            chat(client, "§f Sell: §7" + ROLLER_CONFIG.targetSellItem.getName().getString());
                        }
                        return 1;
                    }))
            );

            // /surv
            dispatcher.register(ClientCommandManager.literal("surv")
                    .then(ClientCommandManager.literal("stop").executes(ctx -> {
                        stopActive("Manual stop."); chat(MinecraftClient.getInstance(), "§cAll modules stopped."); return 1;
                    }))
                    .then(ClientCommandManager.literal("reload").executes(ctx -> {
                        SurvivalConfig.reload(); chat(MinecraftClient.getInstance(), "§bConfig reloaded."); return 1;
                    }))
            );
        });

        // ── Tick ─────────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (menuKey.wasPressed() && client.currentScreen == null)
                client.setScreen(new SurvivalMenuScreen());

            if (stopKey.wasPressed() && activeModule != null) {
                stopActive("Stopped by keybind [K].");
            }

            // Keep cursor unlocked while a module is running
            if (mouseUngrabbed && activeModule != null && client.mouse.isCursorLocked()) {
                client.mouse.unlockCursor();
            }

            if (activeModule != null && !activeModule.isStopped()) {
                activeModule.onTick(client);
                // Check if module stopped itself during this tick
                if (activeModule != null && activeModule.isStopped()) {
                    activeModule = null;
                    relockMouse(client);
                }
            }
        });

        // ── HUD ──────────────────────────────────────────────────────
        HudRenderCallback.EVENT.register((context, delta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (activeModule != null && !activeModule.isStopped())
                activeModule.renderHud(context, client, 10, 10);
        });
    }

    // =================================================================
    //  Roller Config → Fresh Module
    // =================================================================

    public static RollerModule createRollerFromConfig() {
        RollerModule roller = new RollerModule();
        roller.setWorkbenchPos(ROLLER_CONFIG.getWorkbenchPos());
        roller.setTargetBuy(ROLLER_CONFIG.targetBuyItem, ROLLER_CONFIG.targetBuyMaxPrice);
        roller.setTargetSell(ROLLER_CONFIG.targetSellItem);
        roller.setTargetWorkbench(ROLLER_CONFIG.targetWorkbench);
        roller.setBreakingTool(ROLLER_CONFIG.targetBreakingTool);
        if (ROLLER_CONFIG.isLookingForEnchantment && ROLLER_CONFIG.targetEnchantmentId != null)
            roller.setEnchantmentTarget(ROLLER_CONFIG.targetEnchantmentId,
                    ROLLER_CONFIG.targetEnchantmentMinLevel, ROLLER_CONFIG.targetBuyMaxPrice);
        return roller;
    }

    // =================================================================
    //  Module Control — with recursion guard
    // =================================================================

    public static void setActiveModule(BaseModule module) {
        if (isStopping) return; // prevent recursion
        if (activeModule != null && !activeModule.isStopped()) {
            isStopping = true;
            activeModule.stop(MinecraftClient.getInstance(), "Replaced by new module.");
            isStopping = false;
        }
        activeModule = module;
    }

    public static BaseModule getActiveModule() { return activeModule; }

    private static void stopActive(String reason) {
        if (isStopping) return; // prevent recursion
        MinecraftClient client = MinecraftClient.getInstance();
        if (activeModule != null && !activeModule.isStopped()) {
            isStopping = true;
            activeModule.stop(client, reason);
            isStopping = false;
        }
        activeModule = null;
        relockMouse(client);
    }

    private static void relockMouse(MinecraftClient client) {
        if (mouseUngrabbed) {
            mouseUngrabbed = false;
            client.mouse.lockCursor();
        }
    }

    private static void chat(MinecraftClient client, String msg) {
        if (client.player != null)
            client.player.sendMessage(Text.literal("§8[§6SurvivalScripts§8] §r" + msg), false);
    }
}