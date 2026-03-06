package jjhenus.clayfarmer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.TradeOffer;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Trades clay balls with Mason villagers.
 * Uses ShulkerIndex to scan, index, refill clay balls (light gray), and deposit emeralds (green).
 * Press K to stop at any time.
 */
public class ClayTraderBot implements ClientModInitializer {

    private enum State {
        IDLE,
        INDEXING,            // Scan & index nearby shulkers
        SCANNING,            // Find mason villagers
        PATHFINDING, // Walk toward target villager
        PATHFINDING_TO_SHULKER,
        APPROACHING,         // Close range — interact
        TRADING,             // Trade screen open
        NEXT_VILLAGER,       // Pick next villager
        REFILLING_CLAY,      // Take clay balls from light gray shulker
        DEPOSITING_EMERALDS  // Put emeralds into green shulker
    }

    private enum SubState { SCAN, OPEN, READ, TRANSFER, DONE }

    // ── Config ───────────────────────────────────────────────────────────
    private static final int SEARCH_RADIUS = 64;
    private static final int SHULKER_SEARCH_RADIUS = 5;
    private static final int APPROACH_DISTANCE = 3;
    private static final int TRADE_DELAY = 3;
    private static final int SHULKER_OPEN_DELAY = 5;
    private static final int SHULKER_TRANSFER_DELAY = 15;
    private static final int PATHING_TIMEOUT = 600;
    private static final int MAX_INTERACT_RETRIES = 20;
    private static final int MAX_OPEN_RETRIES = 10;
    private static final int WATCHDOG_TIMEOUT = 400;
    private static final int EMERALD_DEPOSIT_THRESHOLD = 128;

    // ── Keybind ──────────────────────────────────────────────────────────
    public static final KeyBinding.Category KEYBIND_CATEGORY = KeyBinding.Category.create(
            net.minecraft.util.Identifier.of("clayfarmer", "main")
    );
    private static KeyBinding stopKeyBind;

    // ── State ────────────────────────────────────────────────────────────
    private static State currentState = State.IDLE;
    private static State stateAfterShulkerPathing = State.IDLE;
    private static SubState subState = SubState.SCAN;
    private static int delayTimer = 0;
    private static boolean mouseUngrabbed = false;

    // Shulker index (shared instance for this bot)
    private static final ShulkerIndex shulkerIndex = new ShulkerIndex();

    // Indexing
    private static List<ShulkerIndex.ShulkerEntry> indexingQueue = new ArrayList<>();
    private static int indexingCursor = 0;
    private static State returnStateAfterIndexing = State.SCANNING;
    private static BlockPos activeShulkerPos = null;
    private static int openRetryCount = 0;

    // Villager tracking
    private static final List<VillagerTarget> villagerQueue = new ArrayList<>();
    private static int villagerCursor = 0;
    private static VillagerTarget currentTarget = null;

    // Trade
    private static int currentTradeIndex = -1;
    private static int tradeAttempts = 0;

    // Pathfinding
    private static int pathingTicks = 0;
    private static int interactRetries = 0;

    // Watchdog
    private static int watchdogTicks = 0;
    private static State lastWatchdogState = State.IDLE;

    // ── Stats ────────────────────────────────────────────────────────────
    private static int totalTradesMade = 0;
    private static int totalClayBallsTraded = 0;
    private static int totalEmeraldsEarned = 0;
    private static int villagersVisited = 0;

    // ── Villager Target ──────────────────────────────────────────────────
    private static class VillagerTarget {
        final UUID uuid;
        final BlockPos lastPos;
        final int level;
        boolean tradeExhausted = false;

        VillagerTarget(VillagerEntity villager) {
            this.uuid = villager.getUuid();
            this.lastPos = villager.getBlockPos();
            this.level = villager.getVillagerData().level();
        }

        VillagerEntity resolve(MinecraftClient client) {
            if (client.world == null) return null;
            for (var entity : client.world.getEntitiesByClass(
                    VillagerEntity.class, new Box(lastPos).expand(16),
                    v -> v.getUuid().equals(uuid))) {
                return entity;
            }
            return null;
        }
    }

    // =====================================================================
    //  Init
    // =====================================================================

    @Override
    public void onInitializeClient() {
        stopKeyBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.claytrade.stop",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_K,
                KEYBIND_CATEGORY
        ));

        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(ClayTraderBot::onTick);
        HudRenderCallback.EVENT.register((dc, tc) -> { if (currentState != State.IDLE) renderHud(dc); });
    }

    // =====================================================================
    //  Commands
    // =====================================================================

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("claytrade")

                    .then(ClientCommandManager.literal("start").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;

                        mouseUngrabbed = true;
                        villagerQueue.clear();
                        villagerCursor = 0;
                        currentTarget = null;
                        watchdogTicks = 0;
                        pathingTicks = 0;
                        interactRetries = 0;
                        openRetryCount = 0;
                        shulkerIndex.clear();

                        // Start by indexing nearby shulkers
                        returnStateAfterIndexing = State.SCANNING;
                        currentState = State.INDEXING;
                        subState = SubState.SCAN;

                        debugChat(client, "\u00a7a\u00a7lStarted! \u00a7rIndexing shulkers...");
                        debugChat(client, "\u00a77Press \u00a7fK\u00a77 to stop.");
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("stop").executes(ctx -> {
                        stopBot(MinecraftClient.getInstance(), "Manual stop.");
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("stats").executes(ctx -> {
                        ctx.getSource().sendFeedback(Text.literal("\u00a76\u00a7l== ClayTrade Stats =="));
                        ctx.getSource().sendFeedback(Text.literal("\u00a7f Trades:   \u00a7a" + totalTradesMade));
                        ctx.getSource().sendFeedback(Text.literal("\u00a7f Clay:     \u00a7b" + totalClayBallsTraded));
                        ctx.getSource().sendFeedback(Text.literal("\u00a7f Emeralds: \u00a72" + totalEmeraldsEarned));
                        ctx.getSource().sendFeedback(Text.literal("\u00a7f Villagers:\u00a7e " + villagersVisited));
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("reset").executes(ctx -> {
                        totalTradesMade = 0; totalClayBallsTraded = 0;
                        totalEmeraldsEarned = 0; villagersVisited = 0;
                        shulkerIndex.clear();
                        ctx.getSource().sendFeedback(Text.literal("\u00a7b[ClayTrade] Stats & index reset!"));
                        return 1;
                    }))

                    .then(ClientCommandManager.literal("scan").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null || client.world == null) return 0;

                        List<VillagerEntity> masons = findMasons(client);
                        ctx.getSource().sendFeedback(Text.literal("\u00a7a[ClayTrade] " + masons.size() + " mason(s) nearby."));
                        for (VillagerEntity v : masons) {
                            double dist = v.getEntityPos().distanceTo(client.player.getEntityPos());
                            ctx.getSource().sendFeedback(Text.literal(
                                    "\u00a77  Lvl " + v.getVillagerData().level() + " @ "
                                            + v.getBlockPos().toShortString() + " (" + String.format("%.0f", dist) + "m)"));
                        }

                        ctx.getSource().sendFeedback(Text.literal("\u00a77 Indexed shulkers: " + shulkerIndex.getIndexedCount() + "/" + shulkerIndex.getTotalCount()));
                        ctx.getSource().sendFeedback(Text.literal("\u00a77 Clay ball sources: " + (shulkerIndex.hasClayBallSources() ? "\u00a7aYes" : "\u00a7cNo")));
                        ctx.getSource().sendFeedback(Text.literal("\u00a77 Emerald deposits:  " + (shulkerIndex.hasEmeraldDepositTargets() ? "\u00a7aYes" : "\u00a7cNo")));
                        return 1;
                    }))
            );
        });
    }

    // =====================================================================
    //  Main Tick
    // =====================================================================

    private static void onTick(MinecraftClient client) {
        if (stopKeyBind.wasPressed() && currentState != State.IDLE) {
            stopBot(client, "Stopped by keybind [K].");
            return;
        }

        if (client.player == null || client.interactionManager == null || currentState == State.IDLE) return;
        if (mouseUngrabbed && client.mouse.isCursorLocked()) client.mouse.unlockCursor();

        // Screen correction
        if (client.currentScreen != null) {
            boolean isMerchant = client.currentScreen instanceof MerchantScreen;
            boolean isShulker = client.currentScreen instanceof ShulkerBoxScreen;
            boolean expectMerchant = currentState == State.TRADING;
            boolean expectShulker =
                    (currentState == State.INDEXING && (subState == SubState.OPEN || subState == SubState.READ))
                            || (currentState == State.REFILLING_CLAY && subState == SubState.TRANSFER)
                            || (currentState == State.DEPOSITING_EMERALDS && subState == SubState.TRANSFER);

            if (isShulker && !expectShulker) { client.player.closeHandledScreen(); delayTimer = 5; return; }
            if (isMerchant && !expectMerchant) { client.player.closeHandledScreen(); delayTimer = 5; return; }
            if (!isMerchant && !isShulker) { client.setScreen(null); delayTimer = 3; return; }
        }

        if (delayTimer > 0) { delayTimer--; watchdogTicks++; return; }

        // Watchdog
        if (currentState != lastWatchdogState) { lastWatchdogState = currentState; watchdogTicks = 0; }
        else watchdogTicks++;
        if (watchdogTicks > WATCHDOG_TIMEOUT) {
            stopBot(client, "Watchdog timeout in " + currentState + "/" + subState);
            return;
        }

        switch (currentState) {
            case INDEXING             -> handleIndexing(client);
            case SCANNING             -> handleScanning(client);
            case PATHFINDING          -> handlePathfinding(client);
            case PATHFINDING_TO_SHULKER -> handlePathfindingToShulker(client); // <-- NEW
            case APPROACHING          -> handleApproaching(client);
            case TRADING              -> handleTrading(client);
            case NEXT_VILLAGER        -> handleNextVillager(client);
            case REFILLING_CLAY       -> handleRefillingClay(client);
            case DEPOSITING_EMERALDS  -> handleDepositingEmeralds(client);
            default -> {}
        }
    }

    // =====================================================================
    //  INDEXING — Scan & index all nearby shulkers
    // =====================================================================

    private static void handleIndexing(MinecraftClient client) {
        ClayfarmerConfig cfg = ClayfarmerConfig.get();

        switch (subState) {
            case SCAN -> {
                shulkerIndex.scanNearbyShulkers(client.player, SHULKER_SEARCH_RADIUS, false);
                indexingQueue = new ArrayList<>(shulkerIndex.getUnindexedShulkers());
                indexingCursor = 0;

                if (indexingQueue.isEmpty()) {
                    debugChat(client, shulkerIndex.getTotalCount() == 0
                            ? "\u00a7eNo shulker boxes found nearby."
                            : "\u00a7aAll " + shulkerIndex.getIndexedCount() + " shulkers indexed.");
                    subState = SubState.DONE;
                    return;
                }

                debugChat(client, "\u00a7bIndexing " + indexingQueue.size() + " shulker(s)...");
                subState = SubState.OPEN;
                delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
            }

            case OPEN -> {
                if (indexingCursor >= indexingQueue.size()) { subState = SubState.DONE; return; }

                ShulkerIndex.ShulkerEntry entry = indexingQueue.get(indexingCursor);
                activeShulkerPos = entry.pos;

                if (client.world.isAir(activeShulkerPos)) { indexingCursor++; return; }

                BlockHitResult hit = new BlockHitResult(activeShulkerPos.toCenterPos(), Direction.UP, activeShulkerPos, false);
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                client.player.swingHand(Hand.MAIN_HAND);

                subState = SubState.READ;
                openRetryCount = 0;
                delayTimer = adaptDelay(SHULKER_TRANSFER_DELAY, client);
            }

            case READ -> {
                if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
                    List<ItemStack> slots = new ArrayList<>();
                    for (int i = 0; i < 27; i++)
                        slots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());

                    long tick = client.world != null ? client.world.getTime() : 0;
                    shulkerIndex.indexShulker(activeShulkerPos, slots, tick);

                    ShulkerIndex.ShulkerEntry entry = null;
                    for (ShulkerIndex.ShulkerEntry e : shulkerIndex.getAllShulkers())
                        if (e.pos.equals(activeShulkerPos)) { entry = e; break; }

                    if (entry != null) {
                        debugChat(client, "\u00a77  " + activeShulkerPos.toShortString()
                                + ": \u00a7b" + entry.clayBallCount + " balls \u00a72" + entry.emeraldCount + " em \u00a7e"
                                + entry.freeSlots() + " free \u00a77[" + entry.role + "]");
                    }

                    client.player.closeHandledScreen();
                    indexingCursor++;
                    openRetryCount = 0;

                    if (indexingCursor < indexingQueue.size()) {
                        subState = SubState.OPEN;
                        delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                    } else {
                        subState = SubState.DONE;
                    }
                } else {
                    openRetryCount++;
                    if (openRetryCount > MAX_OPEN_RETRIES) {
                        debugChat(client, "\u00a7cFailed to open shulker at " + activeShulkerPos.toShortString() + ", skipping.");
                        indexingCursor++;
                        openRetryCount = 0;
                    }
                    subState = SubState.OPEN;
                    delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                }
            }

            case DONE -> {
                debugChat(client, "\u00a7aIndex complete: " + shulkerIndex.getIndexedCount() + " shulkers."
                        + (shulkerIndex.hasClayBallSources() ? " \u00a7bClay: Yes" : " \u00a7cClay: No")
                        + (shulkerIndex.hasEmeraldDepositTargets() ? " \u00a72Em: Yes" : " \u00a7cEm: No"));
                currentState = returnStateAfterIndexing;
                subState = SubState.SCAN;
            }
        }
    }

    // =====================================================================
    //  SCANNING
    // =====================================================================

    private static void handleScanning(MinecraftClient client) {
        List<VillagerEntity> masons = findMasons(client);
        if (masons.isEmpty()) { stopBot(client, "No masons within " + SEARCH_RADIUS + " blocks."); return; }

        Vec3d pos = client.player.getEntityPos();
        masons.sort(Comparator.comparingDouble(v -> v.getEntityPos().squaredDistanceTo(pos)));

        villagerQueue.clear();
        for (VillagerEntity m : masons) villagerQueue.add(new VillagerTarget(m));
        villagerCursor = 0;

        debugChat(client, "\u00a7aFound " + masons.size() + " mason(s). Trading...");
        currentState = State.NEXT_VILLAGER;
    }

    // =====================================================================
    //  NEXT_VILLAGER
    // =====================================================================

    private static void handleNextVillager(MinecraftClient client) {
        // Deposit emeralds if inventory getting full
        int emeralds = countPlayerItem(client.player, Items.EMERALD);
        if (emeralds > EMERALD_DEPOSIT_THRESHOLD && shulkerIndex.hasEmeraldDepositTargets()) {
            ShulkerIndex.ShulkerEntry target = shulkerIndex.pollNextEmeraldDeposit();
            if (target != null) {
                debugChat(client, "\u00a72" + emeralds + " emeralds, depositing...");
                activeShulkerPos = target.pos;
                stateAfterShulkerPathing = State.DEPOSITING_EMERALDS; // <-- Route to walk first
                currentState = State.DEPOSITING_EMERALDS;
                subState = SubState.OPEN;
                openRetryCount = 0;
                delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                return;
            }
        }

        // Refill clay if out
        int clay = countPlayerClayBalls(client.player);
        if (clay == 0) {
            if (shulkerIndex.hasClayBallSources()) {
                ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextClayBallSource();
                if (source != null) {
                    debugChat(client, "\u00a7eOut of clay, refilling...");
                    activeShulkerPos = source.pos;
                    stateAfterShulkerPathing = State.REFILLING_CLAY;
                    currentState = State.REFILLING_CLAY;
                    subState = SubState.OPEN;
                    openRetryCount = 0;
                    delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                    return;
                }
            }
            // Try re-indexing in case new shulkers appeared
            debugChat(client, "\u00a7cNo clay, re-indexing shulkers...");
            returnStateAfterIndexing = State.NEXT_VILLAGER;
            currentState = State.INDEXING;
            subState = SubState.SCAN;
            return;
        }

        // Find next non-exhausted villager
        while (villagerCursor < villagerQueue.size()) {
            VillagerTarget target = villagerQueue.get(villagerCursor);
            if (!target.tradeExhausted) {
                currentTarget = target;
                villagersVisited++;
                debugChat(client, "\u00a7eMason #" + (villagerCursor + 1) + "/" + villagerQueue.size()
                        + " @ " + target.lastPos.toShortString() + " (Lvl " + target.level + ")");
                pathingTicks = 0;
                currentState = State.PATHFINDING;
                return;
            }
            villagerCursor++;
        }

        debugChat(client, "\u00a7eAll villagers exhausted. Rescanning in 5s...");
        currentState = State.SCANNING;
        delayTimer = 100;
    }

    // =====================================================================
    //  PATHFINDING
    // =====================================================================

    private static void handlePathfinding(MinecraftClient client) {
        pathingTicks++;
        if (pathingTicks > PATHING_TIMEOUT) {
            stopWalking(client);
            if (currentTarget != null) currentTarget.tradeExhausted = true;
            villagerCursor++; currentState = State.NEXT_VILLAGER; delayTimer = 5; return;
        }
        if (currentTarget == null) { stopWalking(client); villagerCursor++; currentState = State.NEXT_VILLAGER; return; }

        VillagerEntity villager = currentTarget.resolve(client);
        if (villager == null) { stopWalking(client); villagerCursor++; currentState = State.NEXT_VILLAGER; return; }

        double dist = client.player.getEntityPos().distanceTo(villager.getEntityPos());
        if (dist <= APPROACH_DISTANCE + 1) {
            stopWalking(client); interactRetries = 0;
            currentState = State.APPROACHING; delayTimer = 5; return;
        }

        lookAtEntity(client, villager);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 6);
    }

    // =====================================================================
    //  APPROACHING
    // =====================================================================

    private static void handleApproaching(MinecraftClient client) {
        if (currentTarget == null) { currentState = State.NEXT_VILLAGER; return; }
        VillagerEntity villager = currentTarget.resolve(client);
        if (villager == null) { villagerCursor++; currentState = State.NEXT_VILLAGER; return; }

        double dist = client.player.getEntityPos().distanceTo(villager.getEntityPos());
        if (dist > 5.0) { pathingTicks = 0; currentState = State.PATHFINDING; return; }

        lookAtEntity(client, villager);
        client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);
        interactRetries++;

        if (interactRetries > MAX_INTERACT_RETRIES) {
            villagerCursor++; currentState = State.NEXT_VILLAGER; delayTimer = 5; return;
        }

        currentState = State.TRADING; currentTradeIndex = -1; tradeAttempts = 0; delayTimer = 10;
    }

    // =====================================================================
    //  TRADING
    // =====================================================================

    private static void handleTrading(MinecraftClient client) {
        if (!(client.currentScreen instanceof MerchantScreen merchantScreen)) {
            if (tradeAttempts < 3) { tradeAttempts++; currentState = State.APPROACHING; delayTimer = 10; }
            else {
                if (currentTarget != null) currentTarget.tradeExhausted = true;
                villagerCursor++; currentState = State.NEXT_VILLAGER; delayTimer = 5;
            }
            return;
        }

        if (currentTradeIndex == -1) {
            currentTradeIndex = findClayTrade(merchantScreen);
            if (currentTradeIndex == -1) {
                client.player.closeHandledScreen();
                if (currentTarget != null) currentTarget.tradeExhausted = true;
                villagerCursor++; currentState = State.NEXT_VILLAGER; delayTimer = 5;
                return;
            }
        }

        var handler = merchantScreen.getScreenHandler();
        var recipes = handler.getRecipes();
        if (currentTradeIndex >= recipes.size()) {
            client.player.closeHandledScreen();
            if (currentTarget != null) currentTarget.tradeExhausted = true;
            villagerCursor++; currentState = State.NEXT_VILLAGER; delayTimer = 5;
            return;
        }

        TradeOffer offer = recipes.get(currentTradeIndex);

        if (offer.isDisabled()) {
            client.player.closeHandledScreen();
            if (currentTarget != null) currentTarget.tradeExhausted = true;
            villagerCursor++; currentState = State.NEXT_VILLAGER; delayTimer = 5;
            return;
        }

        int required = offer.getOriginalFirstBuyItem().getCount();
        int available = countPlayerClayBalls(client.player);
        if (available < required) {
            client.player.closeHandledScreen();
            if (shulkerIndex.hasClayBallSources()) {
                ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextClayBallSource();
                if (source != null) {
                    debugChat(client, "\u00a7eLow on clay, refilling...");
                    activeShulkerPos = source.pos;
                    stateAfterShulkerPathing = State.REFILLING_CLAY;
                    currentState = State.REFILLING_CLAY;
                    subState = SubState.OPEN;
                    openRetryCount = 0;
                    delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                    return;
                }
            }
            stopBot(client, "Out of clay balls!");
            return;
        }

        handler.setRecipeIndex(currentTradeIndex);
        if (client.getNetworkHandler() != null)
            client.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(currentTradeIndex));

        client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);
        totalTradesMade++;
        totalClayBallsTraded += required;
        totalEmeraldsEarned += offer.getSellItem().getCount();
        watchdogTicks = 0;
        delayTimer = TRADE_DELAY;
    }

    // =====================================================================
    //  REFILLING_CLAY — open light gray shulker, take clay balls
    // =====================================================================

    private static void handleRefillingClay(MinecraftClient client) {
        switch (subState) {
            case OPEN -> {
                if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
                    debugChat(client, "\u00a7cClay shulker gone!");
                    currentState = State.NEXT_VILLAGER; return;
                }
                openShulkerAt(client, activeShulkerPos);
                subState = SubState.TRANSFER;
                openRetryCount = 0;
                delayTimer = adaptDelay(SHULKER_TRANSFER_DELAY, client);
            }
            case TRANSFER -> {
                if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
                    int syncId = shulkerScreen.getScreenHandler().syncId;
                    int transferred = 0;

                    for (int i = 0; i < 27; i++) {
                        ItemStack stack = shulkerScreen.getScreenHandler().getSlot(i).getStack();
                        if (stack.isOf(Items.CLAY_BALL)) {
                            client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                            transferred += stack.getCount();
                        }
                    }

                    // Re-index this shulker after transfer
                    List<ItemStack> updatedSlots = new ArrayList<>();
                    for (int i = 0; i < 27; i++)
                        updatedSlots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
                    long tick = client.world != null ? client.world.getTime() : 0;
                    shulkerIndex.indexShulker(activeShulkerPos, updatedSlots, tick);

                    client.player.closeHandledScreen();
                    debugChat(client, transferred > 0
                            ? "\u00a7aRefilled " + transferred + " clay balls."
                            : "\u00a7cShulker had no clay balls!");

                    watchdogTicks = 0;
                    currentState = State.NEXT_VILLAGER;
                    subState = SubState.SCAN;
                    delayTimer = 5;
                } else {
                    openRetryCount++;
                    if (openRetryCount > MAX_OPEN_RETRIES) {
                        debugChat(client, "\u00a7cFailed to open clay shulker!");
                        currentState = State.NEXT_VILLAGER; subState = SubState.SCAN; delayTimer = 5;
                        return;
                    }
                    subState = SubState.OPEN;
                    delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                }
            }
        }
    }

    // =====================================================================
    //  DEPOSITING_EMERALDS — open green shulker, deposit emeralds
    // =====================================================================

    private static void handleDepositingEmeralds(MinecraftClient client) {
        switch (subState) {
            case OPEN -> {
                if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
                    debugChat(client, "\u00a7cEmerald shulker gone!");
                    currentState = State.NEXT_VILLAGER; return;
                }
                openShulkerAt(client, activeShulkerPos);
                subState = SubState.TRANSFER;
                openRetryCount = 0;
                delayTimer = adaptDelay(SHULKER_TRANSFER_DELAY, client);
            }
            case TRANSFER -> {
                if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
                    int syncId = shulkerScreen.getScreenHandler().syncId;
                    int deposited = 0;

                    int totalSlots = shulkerScreen.getScreenHandler().slots.size();
                    for (int i = 27; i < totalSlots; i++) {
                        ItemStack stack = shulkerScreen.getScreenHandler().getSlot(i).getStack();
                        if (stack.isOf(Items.EMERALD)) {
                            client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                            deposited += stack.getCount();
                        }
                    }

                    // Re-index this shulker after transfer
                    List<ItemStack> updatedSlots = new ArrayList<>();
                    for (int i = 0; i < 27; i++)
                        updatedSlots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
                    long tick = client.world != null ? client.world.getTime() : 0;
                    shulkerIndex.indexShulker(activeShulkerPos, updatedSlots, tick);

                    client.player.closeHandledScreen();
                    debugChat(client, deposited > 0
                            ? "\u00a72Deposited " + deposited + " emeralds."
                            : "\u00a7eNo emeralds to deposit.");

                    watchdogTicks = 0;
                    currentState = State.NEXT_VILLAGER;
                    subState = SubState.SCAN;
                    delayTimer = 5;
                } else {
                    openRetryCount++;
                    if (openRetryCount > MAX_OPEN_RETRIES) {
                        debugChat(client, "\u00a7cFailed to open emerald shulker!");
                        currentState = State.NEXT_VILLAGER; subState = SubState.SCAN; delayTimer = 5;
                        return;
                    }
                    subState = SubState.OPEN;
                    delayTimer = adaptDelay(SHULKER_OPEN_DELAY, client);
                }
            }
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static void openShulkerAt(MinecraftClient client, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static int adaptDelay(int baseTicks, MinecraftClient client) {
        ClayfarmerConfig cfg = ClayfarmerConfig.get();
        if (cfg.adaptiveDelayPerPing <= 0 || client.getNetworkHandler() == null) return baseTicks;
        var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        if (entry == null) return baseTicks;
        int pingMs = entry.getLatency();
        int extra = (int) Math.ceil((pingMs / 50.0) * cfg.adaptiveDelayPerPing);
        return baseTicks + extra;
    }

    private static List<VillagerEntity> findMasons(MinecraftClient client) {
        if (client.world == null || client.player == null) return List.of();
        Box searchBox = new Box(client.player.getBlockPos()).expand(SEARCH_RADIUS);
        return client.world.getEntitiesByClass(VillagerEntity.class, searchBox, villager -> {
            VillagerData data = villager.getVillagerData();
            return data.profession().matchesId(net.minecraft.util.Identifier.ofVanilla("mason"))
                    && villager.isAlive() && !villager.isBaby();
        });
    }

    private static int findClayTrade(MerchantScreen screen) {
        var recipes = screen.getScreenHandler().getRecipes();
        for (int i = 0; i < recipes.size(); i++)
            if (recipes.get(i).getOriginalFirstBuyItem().isOf(Items.CLAY_BALL)) return i;
        return -1;
    }

    private static void stopWalking(MinecraftClient client) {
        if (client.player != null) {
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }
    }

    private static void lookAtEntity(MinecraftClient client, VillagerEntity villager) {
        Vec3d target = villager.getEntityPos().add(0, villager.getStandingEyeHeight() * 0.9, 0);
        lookAtPos(client, target);
    }

    private static int countPlayerClayBalls(ClientPlayerEntity player) { return countPlayerItem(player, Items.CLAY_BALL); }

    private static int countPlayerItem(ClientPlayerEntity player, net.minecraft.item.Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) { if (player.getInventory().getStack(i).isOf(item)) count += player.getInventory().getStack(i).getCount(); }
        if (player.getOffHandStack().isOf(item)) count += player.getOffHandStack().getCount();
        return count;
    }

    private static void handlePathfindingToShulker(MinecraftClient client) {
        pathingTicks++;
        if (pathingTicks > PATHING_TIMEOUT) {
            stopWalking(client);
            debugChat(client, "\u00a7cFailed to reach shulker in time. Skipping.");
            currentState = State.NEXT_VILLAGER; delayTimer = 5; return;
        }
        if (activeShulkerPos == null || client.player == null) {
            stopWalking(client);
            currentState = State.NEXT_VILLAGER; return;
        }

        Vec3d playerPos = client.player.getEntityPos();
        Vec3d targetPos = activeShulkerPos.toCenterPos();
        double dist = playerPos.distanceTo(targetPos);

        // If we are within 4 blocks, stop walking and start the interaction
        if (dist <= APPROACH_DISTANCE + 1.0) {
            stopWalking(client);
            currentState = stateAfterShulkerPathing;
            subState = SubState.OPEN;
            openRetryCount = 0;
            delayTimer = 5;
            return;
        }

        lookAtPos(client, targetPos);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 6);
    }

    private static void lookAtPos(MinecraftClient client, Vec3d target) {
        if (client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        Vec3d diff = target.subtract(eye).normalize();
        client.player.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        client.player.setPitch((float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z))));
    }


    // =====================================================================
    //  Stop Bot
    // =====================================================================

    private static void stopBot(MinecraftClient client, String reason) {
        stopWalking(client);
        if (client.currentScreen != null && client.player != null) client.player.closeHandledScreen();
        currentState = State.IDLE; subState = SubState.SCAN;
        delayTimer = 0; watchdogTicks = 0;
        mouseUngrabbed = false;
        client.mouse.lockCursor();
        if (client.player != null) {
            debugChat(client, "\u00a7c\u00a7lStopped: \u00a7r\u00a7c" + reason);
            debugChat(client, "\u00a77 Trades: \u00a7a" + totalTradesMade
                    + " \u00a77Clay: \u00a7b" + totalClayBallsTraded
                    + " \u00a77Emeralds: \u00a72" + totalEmeraldsEarned);
        }
    }

    // =====================================================================
    //  HUD
    // =====================================================================

    private static void renderHud(net.minecraft.client.gui.DrawContext drawContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        int x = 10, y = 120, h = 11, c = 0xFFFFFF;

        String s = switch (currentState) {
            case INDEXING             -> "\u00a79Indexing (" + indexingCursor + "/" + indexingQueue.size() + ")";
            case SCANNING            -> "\u00a79Scanning";
            case PATHFINDING         -> "\u00a7ePathing (" + pathingTicks + "t)";
            case PATHFINDING_TO_SHULKER -> "\u00a7eWalking to Shulker (" + pathingTicks + "t)";
            case APPROACHING         -> "\u00a76Approaching";
            case TRADING             -> "\u00a7aTrading";
            case NEXT_VILLAGER       -> "\u00a7bNext Villager";
            case REFILLING_CLAY      -> "\u00a7eRefilling Clay";
            case DEPOSITING_EMERALDS -> "\u00a72Storing Emeralds";
            default                  -> "\u00a77Idle";
        };
        drawContext.drawText(client.textRenderer, "\u00a7f\u00a7lClayTrade \u00a7r" + s, x, y, c, true); y += h;
        drawContext.drawText(client.textRenderer, "\u00a7f Trades: \u00a7a" + totalTradesMade, x, y, c, true); y += h;
        drawContext.drawText(client.textRenderer, "\u00a7f Emeralds: \u00a72" + totalEmeraldsEarned, x, y, c, true); y += h;

        int clay = countPlayerClayBalls(client.player);
        int em = countPlayerItem(client.player, Items.EMERALD);
        drawContext.drawText(client.textRenderer, "\u00a7f Clay: \u00a7b" + clay + "  \u00a7fEm: \u00a72" + em, x, y, c, true); y += h;
        drawContext.drawText(client.textRenderer,
                "\u00a7f Villagers: \u00a7e" + (villagerCursor + 1) + "/" + villagerQueue.size(), x, y, c, true); y += h;
        drawContext.drawText(client.textRenderer,
                "\u00a7f Shulkers: \u00a76" + shulkerIndex.getIndexedCount() + "/" + shulkerIndex.getTotalCount(), x, y, c, true); y += h;
        drawContext.drawText(client.textRenderer, "\u00a78[K] to stop", x, y, 0x888888, true);
    }

    private static void debugChat(MinecraftClient client, String message) {
        if (client.player != null)
            client.player.sendMessage(Text.literal("\u00a78[\u00a72ClayTrade\u00a78] \u00a7r" + message), false);
    }
}