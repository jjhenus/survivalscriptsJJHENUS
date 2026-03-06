package jjhenus.clayfarmer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

public class TradeRollerBot implements ClientModInitializer {

    private enum State {
        IDLE,
        BREAKING,
        COLLECTING_ITEM,
        PLACING,
        WAITING_FOR_JOB,
        INTERACTING,
        CHECKING,
        LOCKING_TRADE
    }

    // ── Keybind ──────────────────────────────────────────────────────────
    private static KeyBinding stopKeyBind;

    // ── Configuration ────────────────────────────────────────────────────
    private static BlockPos workbenchPos = null;

    // Default setup
    private static Item targetBuyItem = Items.CLAY_BALL;
    private static int targetBuyMaxPrice = 10;
    private static Item targetSellItem = Items.EMERALD;
    private static Item targetWorkbench = Items.STONECUTTER;
    private static Item targetBreakingTool = null; // null = Auto-select best tool

    // Enchantment targeting
    private static Identifier targetEnchantmentId = null;
    private static int targetEnchantmentMinLevel = 1;
    private static boolean isLookingForEnchantment = false;

    // ── State ────────────────────────────────────────────────────────────
    private static State currentState = State.IDLE;
    private static int delayTimer = 0;
    private static int waitTicks = 0;
    private static boolean isBreaking = false;
    private static int rollAttempts = 0;
    private static int matchedTradeIndex = -1;

    @Override
    public void onInitializeClient() {
        stopKeyBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.claytrade.roller_stop",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_J,
                ClayTraderBot.KEYBIND_CATEGORY
        ));

        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(TradeRollerBot::onTick);
        HudRenderCallback.EVENT.register((dc, tc) -> { if (currentState != State.IDLE) renderHud(dc); });
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("traderoller")

                    // 1. Target the workbench position
                    .then(ClientCommandManager.literal("pos").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.crosshairTarget instanceof BlockHitResult hit) {
                            workbenchPos = hit.getBlockPos().offset(hit.getSide());
                            debugChat(client, "\u00a7aWorkbench target set to empty space at: " + workbenchPos.toShortString());
                        } else {
                            debugChat(client, "\u00a7cPlease look at the floor block where the workbench should be placed.");
                        }
                        return 1;
                    }))

                    // 2. Configure the breaking tool based on your currently held item
                    .then(ClientCommandManager.literal("tool").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            ItemStack heldStack = client.player.getMainHandStack();
                            if (heldStack.isEmpty()) {
                                targetBreakingTool = null;
                                debugChat(client, "\u00a7eBreaking tool set to: \u00a7aAuto (Best Tool)");
                            } else {
                                targetBreakingTool = heldStack.getItem();
                                debugChat(client, "\u00a7eBreaking tool set to: \u00a7f" + targetBreakingTool.getName().getString());
                            }
                        }
                        return 1;
                    }))

                    // 3. Configure the buy item and max price
                    .then(ClientCommandManager.literal("buy")
                            .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                    .then(ClientCommandManager.argument("maxPrice", IntegerArgumentType.integer(1, 64))
                                            .executes(ctx -> {
                                                String itemName = StringArgumentType.getString(ctx, "item");
                                                targetBuyMaxPrice = IntegerArgumentType.getInteger(ctx, "maxPrice");
                                                targetBuyItem = parseItem(itemName);
                                                isLookingForEnchantment = false;
                                                debugChat(MinecraftClient.getInstance(), "\u00a7bTarget Buy: \u00a7f" + targetBuyMaxPrice + "x " + targetBuyItem.getName().getString());
                                                return 1;
                                            }))))

                    // 4. Configure the sell item
                    .then(ClientCommandManager.literal("sell")
                            .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                    .executes(ctx -> {
                                        targetSellItem = parseItem(StringArgumentType.getString(ctx, "item"));
                                        isLookingForEnchantment = false;
                                        debugChat(MinecraftClient.getInstance(), "\u00a7aTarget Sell: \u00a7f" + targetSellItem.getName().getString());
                                        return 1;
                                    })))

                    // 5. Configure the workbench item
                    .then(ClientCommandManager.literal("block")
                            .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                    .executes(ctx -> {
                                        targetWorkbench = parseItem(StringArgumentType.getString(ctx, "item"));
                                        debugChat(MinecraftClient.getInstance(), "\u00a7eWorkbench block set to: \u00a7f" + targetWorkbench.getName().getString());
                                        return 1;
                                    })))

                    // 6. Configure an exact Enchantment search (For Librarians)
                    .then(ClientCommandManager.literal("enchant")
                            .then(ClientCommandManager.argument("enchantment", StringArgumentType.string())
                                    .then(ClientCommandManager.argument("minLevel", IntegerArgumentType.integer(1, 10))
                                            .then(ClientCommandManager.argument("maxPrice", IntegerArgumentType.integer(1, 64))
                                                    .executes(ctx -> {
                                                        String enchantName = StringArgumentType.getString(ctx, "enchantment");
                                                        targetEnchantmentMinLevel = IntegerArgumentType.getInteger(ctx, "minLevel");
                                                        targetBuyMaxPrice = IntegerArgumentType.getInteger(ctx, "maxPrice");

                                                        targetEnchantmentId = enchantName.contains(":") ?
                                                                Identifier.of(enchantName.split(":")[0], enchantName.split(":")[1]) :
                                                                Identifier.ofVanilla(enchantName);

                                                        isLookingForEnchantment = true;
                                                        targetWorkbench = Items.LECTERN;

                                                        debugChat(MinecraftClient.getInstance(), "\u00a7dTargeting Enchantment: \u00a7f"
                                                                + targetEnchantmentId.getPath() + " (Lvl " + targetEnchantmentMinLevel
                                                                + "+) for max \u00a7a" + targetBuyMaxPrice + " Emeralds");
                                                        return 1;
                                                    })))))

                    // Start the bot
                    .then(ClientCommandManager.literal("start").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (workbenchPos == null) {
                            debugChat(client, "\u00a7cSetup incomplete! Use '/traderoller pos' first.");
                            return 0;
                        }

                        if (isLookingForEnchantment) {
                            if (findItemInInventory(client.player, Items.EMERALD) == -1 || findItemInInventory(client.player, Items.BOOK) == -1) {
                                debugChat(client, "\u00a7cWARNING: You don't have Emeralds and/or Books in your inventory! You won't be able to lock the trade.");
                                return 0;
                            }
                        }

                        rollAttempts = 0;
                        isBreaking = false;
                        matchedTradeIndex = -1;
                        currentState = State.BREAKING;
                        debugChat(client, "\u00a7a\u00a7lTrade Roller Started! \u00a7r\u00a77Press \u00a7fJ\u00a77 to stop.");
                        return 1;
                    }))

                    // Stop the bot
                    .then(ClientCommandManager.literal("stop").executes(ctx -> {
                        stopBot(MinecraftClient.getInstance(), "Manual stop.");
                        return 1;
                    }))
            );
        });
    }

    private static void onTick(MinecraftClient client) {
        if (stopKeyBind.wasPressed() && currentState != State.IDLE) {
            stopBot(client, "Stopped by keybind [J].");
            return;
        }

        if (client.player == null || client.interactionManager == null || currentState == State.IDLE) return;
        if (delayTimer > 0) { delayTimer--; return; }

        switch (currentState) {
            case BREAKING -> handleBreaking(client);
            case COLLECTING_ITEM -> handleCollectingItem(client);
            case PLACING -> handlePlacing(client);
            case WAITING_FOR_JOB -> handleWaitingForJob(client);
            case INTERACTING -> handleInteracting(client);
            case CHECKING -> handleChecking(client);
            case LOCKING_TRADE -> handleLockingTrade(client);
            default -> {}
        }
    }

    private static void handleBreaking(MinecraftClient client) {
        if (client.world.isAir(workbenchPos)) {
            isBreaking = false;
            currentState = State.COLLECTING_ITEM;
            waitTicks = 0;
            delayTimer = 5;
            return;
        }

        if (!equipTool(client, workbenchPos)) {
            stopBot(client, "Missing specified breaking tool (" + targetBreakingTool.getName().getString() + ") in hotbar!");
            return;
        }

        lookAtPos(client, workbenchPos.toCenterPos());

        if (!isBreaking) {
            client.interactionManager.attackBlock(workbenchPos, Direction.UP);
            isBreaking = true;
        } else {
            client.interactionManager.updateBlockBreakingProgress(workbenchPos, Direction.UP);
        }
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static void handleCollectingItem(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        Box searchBox = new Box(workbenchPos).expand(4.0);
        ItemEntity targetItem = null;

        for (ItemEntity itemEntity : client.world.getEntitiesByClass(ItemEntity.class, searchBox, e -> true)) {
            if (itemEntity.getStack().isOf(targetWorkbench)) {
                targetItem = itemEntity;
                break;
            }
        }

        if (targetItem != null) {
            double dist = client.player.getEntityPos().distanceTo(targetItem.getEntityPos());

            if (dist > 1.2) {
                lookAtPos(client, targetItem.getEntityPos());
                client.options.forwardKey.setPressed(true);
            } else {
                client.options.forwardKey.setPressed(false);
            }

            waitTicks++;
            if (waitTicks > 60) {
                client.options.forwardKey.setPressed(false);
                currentState = State.PLACING;
                waitTicks = 0;
            }
        } else {
            client.options.forwardKey.setPressed(false);
            currentState = State.PLACING;
            waitTicks = 0;
            delayTimer = 5;
        }
    }

    private static void handlePlacing(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        double dist = client.player.getEyePos().distanceTo(workbenchPos.toCenterPos());

        if (dist > 3.5) {
            lookAtPos(client, workbenchPos.toCenterPos());
            client.options.forwardKey.setPressed(true);
            client.options.backKey.setPressed(false);
            return;
        } else if (dist < 1.5) {
            lookAtPos(client, workbenchPos.toCenterPos());
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(true);
            return;
        } else {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
        }

        if (!client.world.isAir(workbenchPos)) {
            currentState = State.WAITING_FOR_JOB;
            waitTicks = 0;
            delayTimer = 10;
            return;
        }

        int slot = findItemInHotbar(client.player, targetWorkbench);
        if (slot == -1) {
            stopBot(client, "Out of workbench blocks (" + targetWorkbench.getName().getString() + ") in hotbar!");
            return;
        }

        client.player.getInventory().setSelectedSlot(slot);
        lookAtPos(client, workbenchPos.toCenterPos());

        BlockHitResult hit = getPlacementHit(client, workbenchPos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);

        rollAttempts++;
        currentState = State.WAITING_FOR_JOB;
        waitTicks = 0;
        delayTimer = 20;
    }

    private static void handleWaitingForJob(MinecraftClient client) {
        VillagerEntity villager = getClosestVillager(client);
        if (villager == null) {
            waitTicks++;
            if (waitTicks > 100) stopBot(client, "No villagers found near the workbench!");
            return;
        }

        var profession = villager.getVillagerData().profession();
        boolean hasJob = !profession.matchesId(Identifier.ofVanilla("none"))
                && !profession.matchesId(Identifier.ofVanilla("nitwit"));

        if (hasJob) {
            currentState = State.INTERACTING;
            delayTimer = 10;
        } else {
            waitTicks++;
            if (waitTicks > 200) {
                debugChat(client, "\u00a7eVillager took too long to claim job. Resetting block...");
                currentState = State.BREAKING;
                waitTicks = 0;
            }
        }
    }

    private static void handleInteracting(MinecraftClient client) {
        VillagerEntity villager = getClosestVillager(client);
        if (villager == null) { stopBot(client, "Lost track of villager."); return; }

        lookAtPos(client, villager.getEyePos());
        client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);

        currentState = State.CHECKING;
        delayTimer = 15;
    }

    private static void handleChecking(MinecraftClient client) {
        if (client.currentScreen instanceof MerchantScreen ms) {
            TradeOfferList offers = ms.getScreenHandler().getRecipes();
            matchedTradeIndex = -1;

            for (int i = 0; i < offers.size(); i++) {
                TradeOffer offer = offers.get(i);
                ItemStack buyItem = offer.getOriginalFirstBuyItem();
                ItemStack sellItem = offer.getSellItem();

                if (buyItem.getCount() > targetBuyMaxPrice) continue;

                if (isLookingForEnchantment) {
                    if (sellItem.isOf(Items.ENCHANTED_BOOK) && buyItem.isOf(Items.EMERALD)) {
                        ItemEnchantmentsComponent enchantments = sellItem.getOrDefault(
                                DataComponentTypes.STORED_ENCHANTMENTS,
                                ItemEnchantmentsComponent.DEFAULT
                        );

                        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
                            if (entry.getKey().isPresent()) {
                                String foundId = entry.getKey().get().getValue().toString();
                                if (foundId.equals(targetEnchantmentId.toString())) {
                                    if (enchantments.getLevel(entry) >= targetEnchantmentMinLevel) {
                                        matchedTradeIndex = i;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    boolean buyMatch = buyItem.isOf(targetBuyItem);
                    boolean sellMatch = sellItem.isOf(targetSellItem);

                    if (buyMatch && sellMatch) {
                        matchedTradeIndex = i;
                        break;
                    }
                }
                if (matchedTradeIndex != -1) break;
            }

            if (matchedTradeIndex != -1) {
                currentState = State.LOCKING_TRADE;
                delayTimer = 5;
            } else {
                client.player.closeHandledScreen();
                currentState = State.BREAKING;
                delayTimer = 5;
            }
        } else {
            currentState = State.INTERACTING;
            delayTimer = 10;
        }
    }

    private static void handleLockingTrade(MinecraftClient client) {
        if (client.currentScreen instanceof MerchantScreen ms && matchedTradeIndex != -1) {
            ms.getScreenHandler().setRecipeIndex(matchedTradeIndex);
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(matchedTradeIndex));
            }

            int syncId = ms.getScreenHandler().syncId;
            client.interactionManager.clickSlot(syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);

            client.player.closeHandledScreen();
            stopBot(client, "\u00a7a\u00a7lTRADE FOUND AND LOCKED! \u00a7r\u00a7aTook " + rollAttempts + " rolls.");

        } else {
            stopBot(client, "\u00a7cError: Screen closed before the trade could be locked.");
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    // Helper to dynamically find the closest villager near the workbench
    private static VillagerEntity getClosestVillager(MinecraftClient client) {
        if (client.world == null || workbenchPos == null) return null;
        Box searchBox = new Box(workbenchPos).expand(4.0);
        VillagerEntity closest = null;
        double minDistance = Double.MAX_VALUE;

        for (Entity entity : client.world.getEntitiesByClass(VillagerEntity.class, searchBox, v -> true)) {
            double dist = entity.squaredDistanceTo(workbenchPos.toCenterPos());
            if (dist < minDistance) {
                minDistance = dist;
                closest = (VillagerEntity) entity;
            }
        }
        return closest;
    }

    private static Item parseItem(String name) {
        Identifier id = name.contains(":") ? Identifier.of(name.split(":")[0], name.split(":")[1]) : Identifier.ofVanilla(name);
        return Registries.ITEM.get(id);
    }

    private static void lookAtPos(MinecraftClient client, Vec3d target) {
        if (client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        Vec3d diff = target.subtract(eye).normalize();
        client.player.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        client.player.setPitch((float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z))));
    }

    private static int findItemInHotbar(net.minecraft.client.network.ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static int findItemInInventory(net.minecraft.client.network.ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static boolean equipTool(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.world == null) return false;

        if (targetBreakingTool != null) {
            int slot = findItemInHotbar(client.player, targetBreakingTool);
            if (slot != -1) {
                client.player.getInventory().setSelectedSlot(slot);
                return true;
            }
            return false;
        }

        BlockState state = client.world.getBlockState(pos);
        float bestSpeed = 1.0f;
        int bestSlot = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot != -1) client.player.getInventory().setSelectedSlot(bestSlot);
        return true;
    }

    private static BlockHitResult getPlacementHit(MinecraftClient client, BlockPos targetPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = targetPos.offset(dir);
            if (client.world != null && !client.world.isAir(neighbor)) {
                return new BlockHitResult(
                        Vec3d.ofCenter(neighbor).add(Vec3d.of(dir.getOpposite().getVector()).multiply(0.5)),
                        dir.getOpposite(), neighbor, false
                );
            }
        }
        return new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
    }

    private static void stopBot(MinecraftClient client, String reason) {
        isBreaking = false;
        currentState = State.IDLE;
        delayTimer = 0;
        if (client.player != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            if (client.currentScreen instanceof MerchantScreen) {
                client.player.closeHandledScreen();
            }
        }
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        debugChat(client, "\u00a7cRoller Stopped: \u00a7f" + reason);
    }

    private static void debugChat(MinecraftClient client, String message) {
        if (client.player != null)
            client.player.sendMessage(Text.literal("\u00a78[\u00a76TradeRoller\u00a78] \u00a7r" + message), false);
    }

    private static void renderHud(net.minecraft.client.gui.DrawContext drawContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        int x = 10, y = 200, h = 11, c = 0xFFFFFF;

        String s = switch (currentState) {
            case BREAKING -> "\u00a7cBreaking Workbench";
            case COLLECTING_ITEM -> "\u00a7dCollecting Workbench";
            case PLACING -> "\u00a7ePlacing Workbench";
            case WAITING_FOR_JOB -> "\u00a76Waiting for Job...";
            case INTERACTING -> "\u00a7bOpening Screen";
            case CHECKING -> "\u00a79Checking Trades";
            case LOCKING_TRADE -> "\u00a7aLocking Trade...";
            default -> "\u00a77Idle";
        };
        drawContext.drawText(client.textRenderer, "\u00a7f\u00a7lTrade Roller \u00a7r" + s, x, y, c, true); y += h;

        if (isLookingForEnchantment) {
            drawContext.drawText(client.textRenderer, "\u00a7f Target: \u00a7d" + targetEnchantmentId.getPath() + " " + targetEnchantmentMinLevel + "+ (\u2264" + targetBuyMaxPrice + " Em)", x, y, c, true); y += h;
        } else {
            drawContext.drawText(client.textRenderer, "\u00a7f Target: \u00a7e" + targetBuyMaxPrice + "x " + targetBuyItem.getName().getString() + " -> " + targetSellItem.getName().getString(), x, y, c, true); y += h;
        }

        drawContext.drawText(client.textRenderer, "\u00a7f Rolls: \u00a7a" + rollAttempts, x, y, c, true); y += h;
        drawContext.drawText(client.textRenderer, "\u00a78[J] to stop", x, y, 0x888888, true);
    }
}