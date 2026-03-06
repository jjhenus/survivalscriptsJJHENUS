package jjhenus.survival.modules;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.util.SurvivalUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.network.ClientPlayerEntity;
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

public class RollerModule extends BaseModule {

    private enum State {
        BREAKING, COLLECTING_ITEM, PLACING, WAITING_FOR_JOB,
        INTERACTING, CHECKING, LOCKING_TRADE
    }

    // ── Configuration (package-visible for command-based setup) ─────
    private BlockPos workbenchPos = null;
    public Item targetBuyItem = Items.CLAY_BALL;
    public int targetBuyMaxPrice = 10;
    public Item targetSellItem = Items.EMERALD;
    public Item targetWorkbench = Items.STONECUTTER;
    public Item targetBreakingTool = null;

    // Enchantment targeting
    public Identifier targetEnchantmentId = null;
    public int targetEnchantmentMinLevel = 1;
    public boolean isLookingForEnchantment = false;

    // ── State ────────────────────────────────────────────────────────
    private State currentState = State.BREAKING;
    private State previousState = null; // for watchdog
    private int waitTicks = 0;
    private boolean isBreaking = false;
    private int rollAttempts = 0;
    private int matchedTradeIndex = -1;
    private static final int ROLLER_WATCHDOG_TIMEOUT = 600; // 30 seconds

    // ── Setters for command-based config ─────────────────────────────

    public void setWorkbenchPos(BlockPos pos) { this.workbenchPos = pos; }
    public BlockPos getWorkbenchPos() { return workbenchPos; }

    public void setTargetBuy(Item item, int maxPrice) {
        this.targetBuyItem = item; this.targetBuyMaxPrice = maxPrice;
        this.isLookingForEnchantment = false;
    }

    public void setTargetSell(Item item) {
        this.targetSellItem = item; this.isLookingForEnchantment = false;
    }

    public void setTargetWorkbench(Item item) { this.targetWorkbench = item; }
    public void setBreakingTool(Item item) { this.targetBreakingTool = item; }

    public void setEnchantmentTarget(Identifier enchId, int minLevel, int maxPrice) {
        this.targetEnchantmentId = enchId;
        this.targetEnchantmentMinLevel = minLevel;
        this.targetBuyMaxPrice = maxPrice;
        this.isLookingForEnchantment = true;
        this.targetWorkbench = Items.LECTERN;
    }

    public int getRollAttempts() { return rollAttempts; }

    // =================================================================
    //  BaseModule Overrides
    // =================================================================

    @Override
    public void onTick(MinecraftClient client) {
        if (stopped) return;
        if (client.player == null || client.interactionManager == null) return;
        if (workbenchPos == null) return;

        // Death check
        if (client.player.isDead()) { stop(client, "Player died!"); return; }

        // Health check
        if (isPlayerInDanger(client)) { stop(client, "§cLow health! Stopping for safety."); return; }

        // Don't interfere with death screen
        if (client.currentScreen instanceof DeathScreen) return;

        if (delayTimer > 0) { delayTimer--; return; }

        // FIX #3: Watchdog — resets on state change
        if (currentState != previousState) {
            previousState = currentState;
            watchdogTicks = 0;
        } else {
            watchdogTicks++;
        }
        if (watchdogTicks > ROLLER_WATCHDOG_TIMEOUT) {
            stop(client, "Watchdog timeout in " + currentState + " (" + watchdogTicks + "t)");
            return;
        }

        switch (currentState) {
            case BREAKING        -> handleBreaking(client);
            case COLLECTING_ITEM -> handleCollectingItem(client);
            case PLACING         -> handlePlacing(client);
            case WAITING_FOR_JOB -> handleWaitingForJob(client);
            case INTERACTING     -> handleInteracting(client);
            case CHECKING        -> handleChecking(client);
            case LOCKING_TRADE   -> handleLockingTrade(client);
        }
    }

    @Override
    public void renderHud(DrawContext ctx, MinecraftClient client, int x, int y) {
        if (client.player == null || stopped) return;
        int h = 11;

        String s = switch (currentState) {
            case BREAKING        -> "§cBreaking Workbench";
            case COLLECTING_ITEM -> "§dCollecting Workbench";
            case PLACING         -> "§ePlacing Workbench";
            case WAITING_FOR_JOB -> "§6Waiting for Job...";
            case INTERACTING     -> "§bOpening Screen";
            case CHECKING        -> "§9Checking Trades";
            case LOCKING_TRADE   -> "§aLocking Trade...";
        };
        ctx.drawText(client.textRenderer, "§6§lSurvival Scripts §r§7| §dRoller §r" + s, x, y, 0xFFFFFF, true);
        y += h;

        if (isLookingForEnchantment && targetEnchantmentId != null) {
            ctx.drawText(client.textRenderer, "§f Target: §d" + targetEnchantmentId.getPath()
                    + " " + targetEnchantmentMinLevel + "+ (≤" + targetBuyMaxPrice + " Em)", x, y, 0xFFFFFF, true);
        } else {
            ctx.drawText(client.textRenderer, "§f Target: §e" + targetBuyMaxPrice + "x "
                    + targetBuyItem.getName().getString() + " -> " + targetSellItem.getName().getString(), x, y, 0xFFFFFF, true);
        }
        y += h;
        ctx.drawText(client.textRenderer, "§f Rolls: §a" + rollAttempts, x, y, 0xFFFFFF, true);
        y += h;
        ctx.drawText(client.textRenderer, "§8[K] to stop", x, y, 0x888888, true);
    }

    @Override
    public void stop(MinecraftClient client, String reason) {
        if (stopped) return;
        stopped = true;

        isBreaking = false;
        SurvivalUtils.stopWalking(client);
        if (client.player != null && client.currentScreen instanceof MerchantScreen)
            client.player.closeHandledScreen();
        if (client.interactionManager != null)
            client.interactionManager.cancelBlockBreaking();
        if (client.player != null)
            client.player.sendMessage(Text.literal("§8[§6TradeRoller§8] §cStopped: §f" + reason), false);
    }

    // =================================================================
    //  BREAKING — Break the workbench block
    // =================================================================

    private void handleBreaking(MinecraftClient client) {
        if (client.world.isAir(workbenchPos)) {
            isBreaking = false;
            currentState = State.COLLECTING_ITEM;
            waitTicks = 0;
            delayTimer = 5;
            return;
        }

        if (!equipTool(client, workbenchPos)) {
            stop(client, "Missing breaking tool in hotbar!");
            return;
        }

        SurvivalUtils.lookAt(client, workbenchPos.toCenterPos());

        if (!isBreaking) {
            client.interactionManager.attackBlock(workbenchPos, Direction.UP);
            isBreaking = true;
        } else {
            client.interactionManager.updateBlockBreakingProgress(workbenchPos, Direction.UP);
        }
        client.player.swingHand(Hand.MAIN_HAND);
    }

    // =================================================================
    //  COLLECTING_ITEM — Pick up the dropped workbench
    // =================================================================

    private void handleCollectingItem(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        Box searchBox = new Box(workbenchPos).expand(4.0);
        ItemEntity targetItem = null;
        for (ItemEntity itemEntity : client.world.getEntitiesByClass(ItemEntity.class, searchBox, e -> true)) {
            if (itemEntity.getStack().isOf(targetWorkbench)) { targetItem = itemEntity; break; }
        }

        if (targetItem != null) {
            double dist = client.player.squaredDistanceTo(targetItem);

            // FIX #9: Safety — don't walk more than 6 blocks from workbench pos
            double distFromWorkbench = client.player.squaredDistanceTo(
                    workbenchPos.getX() + 0.5, workbenchPos.getY() + 0.5, workbenchPos.getZ() + 0.5);
            if (distFromWorkbench > 36) { // 6^2
                SurvivalUtils.stopWalking(client);
                currentState = State.PLACING;
                waitTicks = 0;
                return;
            }

            if (dist > 1.44) { // 1.2^2
                SurvivalUtils.lookAt(client, new Vec3d(targetItem.getX(), targetItem.getY(), targetItem.getZ()));
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

    // =================================================================
    //  PLACING — Place the workbench block
    // =================================================================

    private void handlePlacing(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        double dist = client.player.getEyePos().distanceTo(workbenchPos.toCenterPos());
        if (dist > 3.5) {
            SurvivalUtils.lookAt(client, workbenchPos.toCenterPos());
            client.options.forwardKey.setPressed(true);
            client.options.backKey.setPressed(false);
            return;
        } else if (dist < 1.5) {
            SurvivalUtils.lookAt(client, workbenchPos.toCenterPos());
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
        if (slot == -1) { stop(client, "Out of workbench blocks (" + targetWorkbench.getName().getString() + ")!"); return; }

        client.player.getInventory().setSelectedSlot(slot);
        SurvivalUtils.lookAt(client, workbenchPos.toCenterPos());

        BlockHitResult hit = getPlacementHit(client, workbenchPos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);

        rollAttempts++;
        currentState = State.WAITING_FOR_JOB;
        waitTicks = 0;
        delayTimer = 20;
    }

    // =================================================================
    //  WAITING_FOR_JOB — Wait for villager to claim the job
    // =================================================================

    private void handleWaitingForJob(MinecraftClient client) {
        VillagerEntity villager = getClosestVillager(client);
        if (villager == null) {
            waitTicks++;
            if (waitTicks > 100) stop(client, "No villagers found near the workbench!");
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
                currentState = State.BREAKING;
                waitTicks = 0;
            }
        }
    }

    // =================================================================
    //  INTERACTING — Open villager trade screen
    // =================================================================

    private void handleInteracting(MinecraftClient client) {
        VillagerEntity villager = getClosestVillager(client);
        if (villager == null) { stop(client, "Lost track of villager."); return; }

        SurvivalUtils.lookAt(client, new Vec3d(villager.getX(), villager.getEyeY(), villager.getZ()));
        client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);

        currentState = State.CHECKING;
        delayTimer = 15;
    }

    // =================================================================
    //  CHECKING — Check if the trade matches
    // =================================================================

    private void handleChecking(MinecraftClient client) {
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
                                ItemEnchantmentsComponent.DEFAULT);
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
                    if (buyItem.isOf(targetBuyItem) && sellItem.isOf(targetSellItem)) {
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

    // =================================================================
    //  LOCKING_TRADE — Execute the matched trade
    // =================================================================

    private void handleLockingTrade(MinecraftClient client) {
        if (client.currentScreen instanceof MerchantScreen ms && matchedTradeIndex != -1) {
            ms.getScreenHandler().setRecipeIndex(matchedTradeIndex);
            if (client.getNetworkHandler() != null)
                client.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(matchedTradeIndex));
            int syncId = ms.getScreenHandler().syncId;
            client.interactionManager.clickSlot(syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);
            client.player.closeHandledScreen();
            stop(client, "§a§lTRADE FOUND AND LOCKED! §r§aTook " + rollAttempts + " rolls.");
        } else {
            stop(client, "Screen closed before trade could be locked.");
        }
    }

    // =================================================================
    //  Helpers
    // =================================================================

    private VillagerEntity getClosestVillager(MinecraftClient client) {
        if (client.world == null || workbenchPos == null) return null;
        Box searchBox = new Box(workbenchPos).expand(4.0);
        VillagerEntity closest = null;
        double minDist = Double.MAX_VALUE;
        for (Entity entity : client.world.getEntitiesByClass(VillagerEntity.class, searchBox, v -> true)) {
            double dist = entity.squaredDistanceTo(workbenchPos.toCenterPos());
            if (dist < minDist) { minDist = dist; closest = (VillagerEntity) entity; }
        }
        return closest;
    }

    public static Item parseItem(String name) {
        Identifier id = name.contains(":") ? Identifier.of(name.split(":")[0], name.split(":")[1]) : Identifier.ofVanilla(name);
        return Registries.ITEM.get(id);
    }

    private int findItemInHotbar(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) if (player.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }

    private boolean equipTool(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.world == null) return false;
        if (targetBreakingTool != null) {
            int slot = findItemInHotbar(client.player, targetBreakingTool);
            if (slot != -1) { client.player.getInventory().setSelectedSlot(slot); return true; }
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        float bestSpeed = 1.0f; int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        if (bestSlot != -1) client.player.getInventory().setSelectedSlot(bestSlot);
        return true;
    }

    private BlockHitResult getPlacementHit(MinecraftClient client, BlockPos targetPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = targetPos.offset(dir);
            if (client.world != null && !client.world.isAir(neighbor)) {
                return new BlockHitResult(
                        Vec3d.ofCenter(neighbor).add(Vec3d.of(dir.getOpposite().getVector()).multiply(0.5)),
                        dir.getOpposite(), neighbor, false);
            }
        }
        return new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
    }
}