package jjhenus.survival.modules;

import jjhenus.survival.ShulkerIndex;
import jjhenus.survival.SurvivalConfig;
import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.util.SurvivalUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.VillagerData;

import java.util.*;

public class TraderModule extends BaseModule {

    // ── States ───────────────────────────────────────────────────────
    private enum State {
        INDEXING, SCANNING, PATHFINDING, PATHFINDING_TO_SHULKER,
        APPROACHING, TRADING, NEXT_VILLAGER, REFILLING_CLAY, DEPOSITING_EMERALDS
    }

    private enum SubState { SCAN, OPEN, READ, TRANSFER, DONE }

    // ── State tracking ───────────────────────────────────────────────
    private State currentState = State.INDEXING;
    private State stateAfterShulkerPathing = State.INDEXING;
    private SubState subState = SubState.SCAN;
    private State returnStateAfterIndexing = State.SCANNING;

    // ── Shulker index ────────────────────────────────────────────────
    private final ShulkerIndex shulkerIndex = SurvivalScriptsClient.SHULKER_INDEX;

    // ── Indexing ─────────────────────────────────────────────────────
    private List<ShulkerIndex.ShulkerEntry> indexingQueue = new ArrayList<>();
    private int indexingCursor = 0;
    private BlockPos activeShulkerPos = null;
    private int openRetryCount = 0;

    // ── Villager tracking ────────────────────────────────────────────
    private final List<VillagerTarget> villagerQueue = new ArrayList<>();
    private int villagerCursor = 0;
    private VillagerTarget currentTarget = null;

    // ── Trade ────────────────────────────────────────────────────────
    private int currentTradeIndex = -1;
    private int tradeAttempts = 0;

    // ── Pathfinding ──────────────────────────────────────────────────
    private int pathingTicks = 0;
    private int interactRetries = 0;

    // ── Stats ────────────────────────────────────────────────────────
    private int totalTradesMade = 0;
    private int totalClayBallsTraded = 0;
    private int totalEmeraldsEarned = 0;
    private int villagersVisited = 0;

    // ── Villager Target ──────────────────────────────────────────────
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

    // =================================================================
    //  BaseModule Overrides
    // =================================================================

    // ── State change tracking for watchdog ─────────────────────────
    private State previousState = null;

    @Override
    public void onTick(MinecraftClient client) {
        if (stopped) return;
        if (client.player == null || client.interactionManager == null) return;

        // FIX: Don't tick if dead — stop the bot
        if (client.player.isDead()) {
            stop(client, "Player died!");
            return;
        }

        // FIX: Health check
        if (isPlayerInDanger(client)) {
            stop(client, "§cLow health! Stopping for safety.");
            return;
        }

        // ── Screen correction ────────────────────────────────────────
        if (client.currentScreen != null) {
            if (client.currentScreen instanceof DeathScreen) return; // never touch death screen
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

        // ── Watchdog — resets on state change ────────────────────────
        if (currentState != previousState) {
            previousState = currentState;
            watchdogTicks = 0;
        } else {
            watchdogTicks++;
        }
        if (watchdogTicks > SurvivalConfig.get().watchdogTimeout) {
            stop(client, "Watchdog timeout in " + currentState + "/" + subState);
            return;
        }

        switch (currentState) {
            case INDEXING              -> handleIndexing(client);
            case SCANNING              -> handleScanning(client);
            case PATHFINDING           -> handlePathfinding(client);
            case PATHFINDING_TO_SHULKER -> handlePathfindingToShulker(client);
            case APPROACHING           -> handleApproaching(client);
            case TRADING               -> handleTrading(client);
            case NEXT_VILLAGER         -> handleNextVillager(client);
            case REFILLING_CLAY        -> handleRefillingClay(client);
            case DEPOSITING_EMERALDS   -> handleDepositingEmeralds(client);
        }
    }

    @Override
    public void renderHud(DrawContext ctx, MinecraftClient client, int x, int y) {
        if (client.player == null || stopped) return;
        int h = 11;

        String s = switch (currentState) {
            case INDEXING              -> "§9Indexing (" + indexingCursor + "/" + indexingQueue.size() + ")";
            case SCANNING              -> "§9Scanning";
            case PATHFINDING           -> "§ePathing (" + pathingTicks + "t)";
            case PATHFINDING_TO_SHULKER -> "§eWalking to Shulker (" + pathingTicks + "t)";
            case APPROACHING           -> "§6Approaching";
            case TRADING               -> "§aTrading";
            case NEXT_VILLAGER         -> "§bNext Villager";
            case REFILLING_CLAY        -> "§eRefilling Clay";
            case DEPOSITING_EMERALDS   -> "§2Storing Emeralds";
        };

        ctx.drawText(client.textRenderer, "§6§lSurvival Scripts §r§7| §2Trader §r" + s, x, y, 0xFFFFFF, true);
        y += h;
        ctx.drawText(client.textRenderer, "§f Trades: §a" + totalTradesMade + "  §fEmeralds: §2" + totalEmeraldsEarned, x, y, 0xFFFFFF, true);
        y += h;

        int clay = countPlayerItem(client.player, Items.CLAY_BALL);
        int em = countPlayerItem(client.player, Items.EMERALD);
        ctx.drawText(client.textRenderer, "§f Clay: §b" + clay + "  §fEm: §2" + em, x, y, 0xFFFFFF, true);
        y += h;

        // FIX #17: Guard against empty villager list
        int vDisplay = villagerQueue.isEmpty() ? 0 : villagerCursor + 1;
        ctx.drawText(client.textRenderer, "§f Villagers: §e" + vDisplay + "/" + villagerQueue.size(), x, y, 0xFFFFFF, true);
        y += h;
        ctx.drawText(client.textRenderer, "§f Shulkers: §6" + shulkerIndex.getIndexedCount() + "/" + shulkerIndex.getTotalCount(), x, y, 0xFFFFFF, true);
        y += h;

        // Health display
        int health = (int) client.player.getHealth();
        String hpColor = health <= 8 ? "§c" : health <= 14 ? "§e" : "§a";
        ctx.drawText(client.textRenderer, "§f HP: " + hpColor + health + "/" + (int) client.player.getMaxHealth(), x, y, 0xFFFFFF, true);
        y += h;
        ctx.drawText(client.textRenderer, "§8[K] to stop", x, y, 0x888888, true);
    }

    @Override
    public void stop(MinecraftClient client, String reason) {
        if (stopped) return;
        stopped = true;

        SurvivalUtils.stopWalking(client);
        if (client.currentScreen != null && client.player != null && !(client.currentScreen instanceof DeathScreen))
            client.player.closeHandledScreen();
        if (client.player != null) {
            debugChat(client, "§c§lStopped: §r§c" + reason);
            debugChat(client, "§7 Trades: §a" + totalTradesMade
                    + " §7Clay: §b" + totalClayBallsTraded
                    + " §7Emeralds: §2" + totalEmeraldsEarned);
        }
    }

    // =================================================================
    //  INDEXING — Scan & index all nearby shulkers
    // =================================================================

    private void handleIndexing(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();

        switch (subState) {
            case SCAN -> {
                shulkerIndex.scanNearbyShulkers(client.player, cfg.shulkerSearchRadius, false);
                indexingQueue = new ArrayList<>(shulkerIndex.getUnindexedShulkers());
                indexingCursor = 0;

                if (indexingQueue.isEmpty()) {
                    debugChat(client, shulkerIndex.getTotalCount() == 0
                            ? "§eNo shulker boxes found nearby."
                            : "§aAll " + shulkerIndex.getIndexedCount() + " shulkers indexed.");
                    subState = SubState.DONE;
                    return;
                }

                debugChat(client, "§bIndexing " + indexingQueue.size() + " shulker(s)...");
                subState = SubState.OPEN;
                delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
            }

            case OPEN -> {
                if (indexingCursor >= indexingQueue.size()) { subState = SubState.DONE; return; }

                ShulkerIndex.ShulkerEntry entry = indexingQueue.get(indexingCursor);
                activeShulkerPos = entry.pos;

                if (client.world.isAir(activeShulkerPos)) { indexingCursor++; return; }

                openShulkerAt(client, activeShulkerPos);
                subState = SubState.READ;
                openRetryCount = 0;
                delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerTransferDelay, client);
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
                        debugChat(client, "§7  " + activeShulkerPos.toShortString()
                                + ": §b" + entry.clayBallCount + " balls §2" + entry.emeraldCount + " em §e"
                                + entry.freeSlots() + " free §7[" + entry.role + "]");
                    }

                    client.player.closeHandledScreen();
                    indexingCursor++;
                    openRetryCount = 0;

                    if (indexingCursor < indexingQueue.size()) {
                        subState = SubState.OPEN;
                        delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
                    } else {
                        subState = SubState.DONE;
                    }
                } else {
                    openRetryCount++;
                    if (openRetryCount > cfg.maxOpenRetries) {
                        debugChat(client, "§cFailed to open shulker at " + activeShulkerPos.toShortString() + ", skipping.");
                        indexingCursor++;
                        openRetryCount = 0;
                    }
                    subState = SubState.OPEN;
                    delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
                }
            }

            case DONE -> {
                debugChat(client, "§aIndex complete: " + shulkerIndex.getIndexedCount() + " shulkers."
                        + (shulkerIndex.hasClayBallSources() ? " §bClay: Yes" : " §cClay: No")
                        + (shulkerIndex.hasEmeraldDepositTargets() ? " §2Em: Yes" : " §cEm: No"));
                currentState = returnStateAfterIndexing;
                subState = SubState.SCAN;
                watchdogTicks = 0;
            }
        }
    }

    // =================================================================
    //  SCANNING — Find mason villagers
    // =================================================================

    private void handleScanning(MinecraftClient client) {
        List<VillagerEntity> masons = findMasons(client);
        if (masons.isEmpty()) { stop(client, "No masons within " + SurvivalConfig.get().searchRadius + " blocks."); return; }

        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        masons.sort(Comparator.comparingDouble(v -> v.squaredDistanceTo(playerPos.x, playerPos.y, playerPos.z)));

        villagerQueue.clear();
        for (VillagerEntity m : masons) villagerQueue.add(new VillagerTarget(m));
        villagerCursor = 0;

        debugChat(client, "§aFound " + masons.size() + " mason(s). Trading...");
        currentState = State.NEXT_VILLAGER;
        watchdogTicks = 0;
    }

    // =================================================================
    //  NEXT_VILLAGER
    // =================================================================

    private void handleNextVillager(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();

        // Deposit emeralds if inventory getting full
        int emeralds = countPlayerItem(client.player, Items.EMERALD);
        if (emeralds > cfg.emeraldDepositThreshold && shulkerIndex.hasEmeraldDepositTargets()) {
            ShulkerIndex.ShulkerEntry target = shulkerIndex.pollNextEmeraldDeposit();
            if (target != null) {
                debugChat(client, "§2" + emeralds + " emeralds, depositing...");
                activeShulkerPos = target.pos;
                stateAfterShulkerPathing = State.DEPOSITING_EMERALDS;
                currentState = State.PATHFINDING_TO_SHULKER;
                pathingTicks = 0;
                watchdogTicks = 0;
                return;
            }
        }

        // Refill clay if out
        int clay = countPlayerItem(client.player, Items.CLAY_BALL);
        if (clay == 0) {
            if (shulkerIndex.hasClayBallSources()) {
                ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextClayBallSource();
                if (source != null) {
                    debugChat(client, "§eOut of clay, refilling...");
                    activeShulkerPos = source.pos;
                    stateAfterShulkerPathing = State.REFILLING_CLAY;
                    currentState = State.PATHFINDING_TO_SHULKER;
                    pathingTicks = 0;
                    watchdogTicks = 0;
                    return;
                }
            }
            debugChat(client, "§cNo clay, re-indexing shulkers...");
            returnStateAfterIndexing = State.NEXT_VILLAGER;
            currentState = State.INDEXING;
            subState = SubState.SCAN;
            watchdogTicks = 0;
            return;
        }

        // Find next non-exhausted villager
        while (villagerCursor < villagerQueue.size()) {
            VillagerTarget target = villagerQueue.get(villagerCursor);
            if (!target.tradeExhausted) {
                currentTarget = target;
                villagersVisited++;
                debugChat(client, "§eMason #" + (villagerCursor + 1) + "/" + villagerQueue.size()
                        + " @ " + target.lastPos.toShortString() + " (Lvl " + target.level + ")");
                pathingTicks = 0;
                currentState = State.PATHFINDING;
                watchdogTicks = 0;
                return;
            }
            villagerCursor++;
        }

        debugChat(client, "§eAll villagers exhausted. Rescanning in 5s...");
        currentState = State.SCANNING;
        delayTimer = 100;
        watchdogTicks = 0;
    }

    // =================================================================
    //  PATHFINDING — Walk toward villager
    // =================================================================

    private void handlePathfinding(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();
        pathingTicks++;

        if (pathingTicks > cfg.pathingTimeout) {
            SurvivalUtils.stopWalking(client);
            if (currentTarget != null) currentTarget.tradeExhausted = true;
            villagerCursor++;
            currentState = State.NEXT_VILLAGER;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        if (currentTarget == null) {
            SurvivalUtils.stopWalking(client);
            villagerCursor++;
            currentState = State.NEXT_VILLAGER;
            return;
        }

        VillagerEntity villager = currentTarget.resolve(client);
        if (villager == null) {
            SurvivalUtils.stopWalking(client);
            villagerCursor++;
            currentState = State.NEXT_VILLAGER;
            return;
        }

        double dist = client.player.squaredDistanceTo(villager);
        double approachDistSq = (cfg.approachDistance + 1.0) * (cfg.approachDistance + 1.0);

        if (dist <= approachDistSq) {
            SurvivalUtils.stopWalking(client);
            interactRetries = 0;
            currentState = State.APPROACHING;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        SurvivalUtils.lookAt(client, new Vec3d(villager.getX(), villager.getEyeY(), villager.getZ()));
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 36); // 6^2 = 36
    }

    // =================================================================
    //  PATHFINDING_TO_SHULKER — Walk toward shulker box
    // =================================================================

    private void handlePathfindingToShulker(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();
        pathingTicks++;

        if (pathingTicks > cfg.pathingTimeout) {
            SurvivalUtils.stopWalking(client);
            debugChat(client, "§cFailed to reach shulker in time. Skipping.");
            currentState = State.NEXT_VILLAGER;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        if (activeShulkerPos == null || client.player == null) {
            SurvivalUtils.stopWalking(client);
            currentState = State.NEXT_VILLAGER;
            return;
        }

        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        Vec3d targetPos = activeShulkerPos.toCenterPos();
        double dist = playerPos.distanceTo(targetPos);

        if (dist <= SurvivalConfig.get().approachDistance + 1.0) {
            SurvivalUtils.stopWalking(client);
            currentState = stateAfterShulkerPathing;
            subState = SubState.OPEN;
            openRetryCount = 0;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        SurvivalUtils.lookAt(client, targetPos);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 6);
    }

    // =================================================================
    //  APPROACHING — Close range, interact with villager
    // =================================================================

    private void handleApproaching(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();

        if (currentTarget == null) { currentState = State.NEXT_VILLAGER; return; }
        VillagerEntity villager = currentTarget.resolve(client);
        if (villager == null) { villagerCursor++; currentState = State.NEXT_VILLAGER; return; }

        double dist = client.player.squaredDistanceTo(villager);
        if (dist > 25.0) { // 5^2
            pathingTicks = 0;
            currentState = State.PATHFINDING;
            return;
        }

        SurvivalUtils.lookAt(client, new Vec3d(villager.getX(), villager.getEyeY(), villager.getZ()));
        client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);
        interactRetries++;

        if (interactRetries > cfg.maxInteractRetries) {
            villagerCursor++;
            currentState = State.NEXT_VILLAGER;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        currentState = State.TRADING;
        currentTradeIndex = -1;
        tradeAttempts = 0;
        delayTimer = 10;
        watchdogTicks = 0;
    }

    // =================================================================
    //  TRADING — Handle merchant screen
    // =================================================================

    private void handleTrading(MinecraftClient client) {
        if (!(client.currentScreen instanceof MerchantScreen merchantScreen)) {
            if (tradeAttempts < 3) {
                tradeAttempts++;
                currentState = State.APPROACHING;
                delayTimer = 10;
            } else {
                if (currentTarget != null) currentTarget.tradeExhausted = true;
                villagerCursor++;
                currentState = State.NEXT_VILLAGER;
                delayTimer = 5;
                watchdogTicks = 0;
            }
            return;
        }

        if (currentTradeIndex == -1) {
            currentTradeIndex = findClayTrade(merchantScreen);
            if (currentTradeIndex == -1) {
                client.player.closeHandledScreen();
                if (currentTarget != null) currentTarget.tradeExhausted = true;
                villagerCursor++;
                currentState = State.NEXT_VILLAGER;
                delayTimer = 5;
                watchdogTicks = 0;
                return;
            }
        }

        var handler = merchantScreen.getScreenHandler();
        var recipes = handler.getRecipes();
        if (currentTradeIndex >= recipes.size()) {
            client.player.closeHandledScreen();
            if (currentTarget != null) currentTarget.tradeExhausted = true;
            villagerCursor++;
            currentState = State.NEXT_VILLAGER;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        TradeOffer offer = recipes.get(currentTradeIndex);

        if (offer.isDisabled()) {
            client.player.closeHandledScreen();
            if (currentTarget != null) currentTarget.tradeExhausted = true;
            villagerCursor++;
            currentState = State.NEXT_VILLAGER;
            delayTimer = 5;
            watchdogTicks = 0;
            return;
        }

        int required = offer.getOriginalFirstBuyItem().getCount();
        int available = countPlayerItem(client.player, Items.CLAY_BALL);
        if (available < required) {
            client.player.closeHandledScreen();
            if (shulkerIndex.hasClayBallSources()) {
                ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextClayBallSource();
                if (source != null) {
                    debugChat(client, "§eLow on clay, refilling...");
                    activeShulkerPos = source.pos;
                    stateAfterShulkerPathing = State.REFILLING_CLAY;
                    currentState = State.PATHFINDING_TO_SHULKER;
                    pathingTicks = 0;
                    watchdogTicks = 0;
                    return;
                }
            }
            stop(client, "Out of clay balls!");
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
        delayTimer = SurvivalConfig.get().tradeDelay;
    }

    // =================================================================
    //  REFILLING_CLAY — Take clay balls from shulker
    // =================================================================

    private void handleRefillingClay(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();

        switch (subState) {
            case OPEN -> {
                if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
                    debugChat(client, "§cClay shulker gone!");
                    currentState = State.NEXT_VILLAGER;
                    watchdogTicks = 0;
                    return;
                }
                openShulkerAt(client, activeShulkerPos);
                subState = SubState.TRANSFER;
                openRetryCount = 0;
                delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerTransferDelay, client);
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

                    // Re-index after transfer
                    List<ItemStack> updatedSlots = new ArrayList<>();
                    for (int i = 0; i < 27; i++)
                        updatedSlots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
                    long tick = client.world != null ? client.world.getTime() : 0;
                    shulkerIndex.indexShulker(activeShulkerPos, updatedSlots, tick);

                    client.player.closeHandledScreen();
                    debugChat(client, transferred > 0
                            ? "§aRefilled " + transferred + " clay balls."
                            : "§cShulker had no clay balls!");

                    watchdogTicks = 0;
                    currentState = State.NEXT_VILLAGER;
                    subState = SubState.SCAN;
                    delayTimer = 5;
                } else {
                    openRetryCount++;
                    if (openRetryCount > cfg.maxOpenRetries) {
                        debugChat(client, "§cFailed to open clay shulker!");
                        currentState = State.NEXT_VILLAGER;
                        subState = SubState.SCAN;
                        delayTimer = 5;
                        watchdogTicks = 0;
                        return;
                    }
                    subState = SubState.OPEN;
                    delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
                }
            }
        }
    }

    // =================================================================
    //  DEPOSITING_EMERALDS — Put emeralds into shulker
    // =================================================================

    private void handleDepositingEmeralds(MinecraftClient client) {
        SurvivalConfig cfg = SurvivalConfig.get();

        switch (subState) {
            case OPEN -> {
                if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
                    debugChat(client, "§cEmerald shulker gone!");
                    currentState = State.NEXT_VILLAGER;
                    watchdogTicks = 0;
                    return;
                }
                openShulkerAt(client, activeShulkerPos);
                subState = SubState.TRANSFER;
                openRetryCount = 0;
                delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerTransferDelay, client);
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

                    // Re-index after transfer
                    List<ItemStack> updatedSlots = new ArrayList<>();
                    for (int i = 0; i < 27; i++)
                        updatedSlots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
                    long tick = client.world != null ? client.world.getTime() : 0;
                    shulkerIndex.indexShulker(activeShulkerPos, updatedSlots, tick);

                    client.player.closeHandledScreen();
                    debugChat(client, deposited > 0
                            ? "§2Deposited " + deposited + " emeralds."
                            : "§eNo emeralds to deposit.");

                    watchdogTicks = 0;
                    currentState = State.NEXT_VILLAGER;
                    subState = SubState.SCAN;
                    delayTimer = 5;
                } else {
                    openRetryCount++;
                    if (openRetryCount > cfg.maxOpenRetries) {
                        debugChat(client, "§cFailed to open emerald shulker!");
                        currentState = State.NEXT_VILLAGER;
                        subState = SubState.SCAN;
                        delayTimer = 5;
                        watchdogTicks = 0;
                        return;
                    }
                    subState = SubState.OPEN;
                    delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
                }
            }
        }
    }

    // =================================================================
    //  Helpers
    // =================================================================

    private void openShulkerAt(MinecraftClient client, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private List<VillagerEntity> findMasons(MinecraftClient client) {
        if (client.world == null || client.player == null) return List.of();
        Box searchBox = client.player.getBoundingBox().expand(SurvivalConfig.get().searchRadius);
        return client.world.getEntitiesByClass(VillagerEntity.class, searchBox, villager -> {
            VillagerData data = villager.getVillagerData();
            return data.profession().matchesId(net.minecraft.util.Identifier.ofVanilla("mason"))
                    && villager.isAlive() && !villager.isBaby();
        });
    }

    private int findClayTrade(MerchantScreen screen) {
        var recipes = screen.getScreenHandler().getRecipes();
        for (int i = 0; i < recipes.size(); i++)
            if (recipes.get(i).getOriginalFirstBuyItem().isOf(Items.CLAY_BALL)) return i;
        return -1;
    }

    private int countPlayerItem(ClientPlayerEntity player, net.minecraft.item.Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        if (player.getOffHandStack().isOf(item)) count += player.getOffHandStack().getCount();
        return count;
    }

    private void debugChat(MinecraftClient client, String message) {
        if (client.player != null)
            client.player.sendMessage(Text.literal("§8[§2ClayTrade§8] §r" + message), false);
    }
}