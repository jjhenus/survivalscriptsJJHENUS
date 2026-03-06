package jjhenus.survival.modules;

import jjhenus.survival.ShulkerIndex;
import jjhenus.survival.SurvivalConfig;
import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.util.SurvivalUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public class FarmerModule extends BaseModule {

	private enum State {
		INDEXING, PLACING, BREAKING, REPAIRING, REFILLING, DEPOSITING
	}

	private enum SubState {
		SCAN, OPEN, READ, TRANSFER, DONE
	}

	// ── State ────────────────────────────────────────────────────────
	private State currentState = State.INDEXING;
	private State previousState = null; // for watchdog reset on state change
	private SubState subState = SubState.SCAN;

	private BlockPos targetPos = null;
	private BlockPos activeShulkerPos = null;

	private float savedPitch = 0f;
	private int savedSlot = 0;
	private boolean cameraOverridden = false;

	private int openRetryCount = 0;

	private List<ShulkerIndex.ShulkerEntry> indexingQueue = new ArrayList<>();
	private int indexingCursor = 0;
	private State returnStateAfterIndexing = State.PLACING;

	private int breakingStallTicks = 0;
	private int placingStallTicks = 0;

	// ── Shulker index (shared) ───────────────────────────────────────
	private final ShulkerIndex shulkerIndex = SurvivalScriptsClient.SHULKER_INDEX;

	// ── Stats ────────────────────────────────────────────────────────
	private int clayMinedCount = 0;
	private int shulkersEmptied = 0;
	private int xpBottlesUsed = 0;
	private long sessionStartTick = 0;
	private long totalSessionTicks = 0;

	private int debugChatCooldown = 0;
	private static final int DEBUG_CHAT_INTERVAL = 100;

	// =================================================================
	//  Preflight
	// =================================================================

	public FarmerModule() {}

	public boolean preflight(MinecraftClient client) {
		if (client.player == null) return false;
		List<String> problems = preflightChecks(client.player);
		if (!problems.isEmpty()) {
			debugChat(client, "§c§lPre-Flight Failed:");
			for (String m : problems) debugChat(client, "  §c✗ " + m);
			return false;
		}
		saveCameraState(client.player);
		sessionStartTick = client.world != null ? client.world.getTime() : 0;
		shulkerIndex.clear();
		return true;
	}

	private List<String> preflightChecks(ClientPlayerEntity player) {
		List<String> problems = new ArrayList<>();
		int shovelSlot = findHotbarItem(player, ItemTags.SHOVELS);
		if (shovelSlot == -1) {
			problems.add("No shovel in hotbar");
		} else {
			ItemStack shovel = player.getInventory().getStack(shovelSlot);
			if (!hasMending(shovel)) problems.add("Shovel does not have Mending");
		}
		boolean hasClay = player.getOffHandStack().isOf(Items.CLAY)
				|| findHotbarItemByItem(player, Items.CLAY) != -1;
		if (!hasClay) problems.add("No clay blocks in hotbar or offhand");
		if (findHotbarItemByItem(player, Items.EXPERIENCE_BOTTLE) == -1)
			problems.add("No XP bottles in hotbar");
		return problems;
	}

	// =================================================================
	//  BaseModule Overrides
	// =================================================================

	@Override
	public void onTick(MinecraftClient client) {
		if (stopped) return;
		if (client.player == null || client.interactionManager == null) return;

		// FIX #10: Don't force-close death screen — stop the bot instead
		if (client.player.isDead()) {
			stop(client, "Player died!");
			return;
		}

		// FIX #16: Health check — stop if low
		if (isPlayerInDanger(client)) {
			stop(client, "§cLow health! Stopping for safety.");
			return;
		}

		// Screen correction — allow death screen, shulker screens when expected
		if (client.currentScreen != null) {
			if (client.currentScreen instanceof DeathScreen) return; // never touch death screen
			boolean isShulkerScreen = client.currentScreen instanceof ShulkerBoxScreen;
			if (!isShulkerScreen) {
				client.setScreen(null);
				delayTimer = 3;
				return;
			}
			boolean expectingShulker =
					(currentState == State.INDEXING && (subState == SubState.OPEN || subState == SubState.READ))
							|| (currentState == State.REFILLING && (subState == SubState.OPEN || subState == SubState.TRANSFER))
							|| (currentState == State.DEPOSITING && (subState == SubState.OPEN || subState == SubState.TRANSFER));
			if (!expectingShulker) {
				client.player.closeHandledScreen();
				delayTimer = 5;
				return;
			}
		}

		if (delayTimer > 0) { delayTimer--; watchdogTicks++; return; }

		if (debugChatCooldown > 0) debugChatCooldown--;

		SurvivalConfig cfg = SurvivalConfig.get();

		// FIX #8: Watchdog resets cleanly on state change
		if (currentState != previousState) {
			previousState = currentState;
			watchdogTicks = 0;
		} else {
			watchdogTicks++;
		}
		if (watchdogTicks > cfg.stateTimeoutTicks) {
			stop(client, "Watchdog timeout in " + currentState + "/" + subState);
			return;
		}

		// Stall correction: BREAKING
		if (currentState == State.BREAKING) {
			if (targetPos != null && !client.world.isAir(targetPos)) {
				breakingStallTicks++;
				if (breakingStallTicks > 60) {
					breakingStallTicks = 0;
					targetPos = null;
					currentState = State.PLACING;
					delayTimer = 5;
					return;
				}
			} else breakingStallTicks = 0;
		} else breakingStallTicks = 0;

		// Stall correction: PLACING
		if (currentState == State.PLACING) {
			placingStallTicks++;
			if (placingStallTicks > 40) {
				placingStallTicks = 0;
				client.player.setPitch(45f);
				delayTimer = 5;
				return;
			}
		} else placingStallTicks = 0;

		// Inventory full -> deposit
		if (currentState == State.PLACING || currentState == State.BREAKING) {
			if (isInventoryNearlyFull(client.player)) {
				debugChat(client, "§6Inventory nearly full, depositing...");
				currentState = State.DEPOSITING;
				subState = SubState.SCAN;
				return;
			}
		}

		switch (currentState) {
			case INDEXING   -> handleIndexing(client);
			case PLACING    -> handlePlacing(client);
			case BREAKING   -> handleBreaking(client);
			case REPAIRING  -> handleRepairing(client);
			case REFILLING  -> handleRefilling(client);
			case DEPOSITING -> handleDepositing(client);
		}
	}

	@Override
	public void renderHud(DrawContext ctx, MinecraftClient client, int x, int y) {
		if (client.player == null || stopped) return;
		SurvivalConfig cfg = SurvivalConfig.get();
		int h = 11;

		String stateStr = switch (currentState) {
			case INDEXING   -> "§9Indexing (" + indexingCursor + "/" + indexingQueue.size() + ")";
			case PLACING    -> "§aPlacing";
			case BREAKING   -> "§eBreaking";
			case REPAIRING  -> "§dRepairing";
			case REFILLING  -> "§6Refilling";
			case DEPOSITING -> "§bDepositing";
		};
		ctx.drawText(client.textRenderer, "§6§lSurvival Scripts §r§7| §aFarmer §r" + stateStr, x, y, 0xFFFFFF, true);
		y += h;
		ctx.drawText(client.textRenderer, "§f Clay Mined: §a" + clayMinedCount, x, y, 0xFFFFFF, true);
		y += h;

		// FIX #18: Guard against negative uptime
		long uptimeTicks = totalSessionTicks;
		if (client.world != null && client.world.getTime() >= sessionStartTick)
			uptimeTicks += client.world.getTime() - sessionStartTick;
		long uptimeSec = Math.max(uptimeTicks / 20, 1);
		double clayPerHour = (clayMinedCount / (double) uptimeSec) * 3600;
		ctx.drawText(client.textRenderer, "§f Rate: §b" + String.format("%.0f", clayPerHour) + "/hr", x, y, 0xFFFFFF, true);
		y += h;

		int pBlocks = ShulkerIndex.countPlayerClayBlocks(client.player);
		int sBlocks = shulkerIndex.getTotalShulkerClayBlocks();
		ctx.drawText(client.textRenderer, "§f Blocks: §a" + pBlocks + " §7inv + §a" + sBlocks + " §7shulk", x, y, 0xFFFFFF, true);
		y += h;

		// Health display
		int health = (int) client.player.getHealth();
		String hpColor = health <= 8 ? "§c" : health <= 14 ? "§e" : "§a";
		ctx.drawText(client.textRenderer, "§f HP: " + hpColor + health + "/" + (int) client.player.getMaxHealth(), x, y, 0xFFFFFF, true);
		y += h;

		int shovelSlot = findHotbarItem(client.player, ItemTags.SHOVELS);
		if (shovelSlot != -1) {
			ItemStack shovel = client.player.getInventory().getStack(shovelSlot);
			int dur = shovel.getMaxDamage() - shovel.getDamage();
			String durColor = dur < cfg.durabilityRepairThreshold ? "§c" : dur < cfg.durabilityRepairedTarget ? "§e" : "§a";
			ctx.drawText(client.textRenderer, "§f Shovel: " + durColor + dur + "/" + shovel.getMaxDamage(), x, y, 0xFFFFFF, true);
			y += h;
		}

		ctx.drawText(client.textRenderer, "§f Shulkers: §6" + shulkerIndex.getIndexedCount() + "/" + shulkerIndex.getTotalCount(), x, y, 0xFFFFFF, true);
	}

	@Override
	public void stop(MinecraftClient client, String reason) {
		if (stopped) return; // FIX #1: prevent double-stop
		stopped = true;

		SurvivalConfig cfg = SurvivalConfig.get();
		if (client.world != null && sessionStartTick > 0)
			totalSessionTicks += client.world.getTime() - sessionStartTick;
		if (client.currentScreen != null && client.player != null && !(client.currentScreen instanceof DeathScreen))
			client.player.closeHandledScreen();
		restoreCameraState(client.player);
		if (cfg.alertChatOnStop && client.player != null)
			debugChat(client, "§c§lStopped: §r§c" + reason);
		if (cfg.alertSoundOnStop && client.player != null)
			client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
	}

	// =================================================================
	//  INDEXING
	// =================================================================

	private void handleIndexing(MinecraftClient client) {
		SurvivalConfig cfg = SurvivalConfig.get();
		switch (subState) {
			case SCAN -> {
				shulkerIndex.scanNearbyShulkers(client.player, cfg.shulkerSearchRadius, cfg.acceptAnyShulkerColor);
				indexingQueue = new ArrayList<>(shulkerIndex.getUnindexedShulkers());
				indexingCursor = 0;
				if (indexingQueue.isEmpty()) {
					debugChat(client, shulkerIndex.getTotalCount() == 0
							? "§eNo shulker boxes found nearby."
							: "§aAll " + shulkerIndex.getIndexedCount() + " shulkers already indexed.");
					subState = SubState.DONE;
					return;
				}
				debugChat(client, "§bFound " + indexingQueue.size() + " shulker(s) to index...");
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
					if (entry != null)
						debugChat(client, "§7  " + activeShulkerPos.toShortString()
								+ ": §a" + entry.clayBlockCount + " blocks §b" + entry.clayBallCount + " balls §e" + entry.freeSlots() + " free §7[" + entry.role + "]");

					client.player.closeHandledScreen();
					indexingCursor++;
					openRetryCount = 0;
					if (indexingCursor < indexingQueue.size()) {
						subState = SubState.OPEN;
						delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
					} else subState = SubState.DONE;
				} else {
					openRetryCount++;
					if (openRetryCount > cfg.maxOpenRetries) { indexingCursor++; openRetryCount = 0; }
					subState = SubState.OPEN;
					delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
				}
			}
			case DONE -> finishIndexing(client);
		}
	}

	private void finishIndexing(MinecraftClient client) {
		if (returnStateAfterIndexing == State.DEPOSITING) {
			currentState = State.DEPOSITING; subState = SubState.SCAN; return;
		}
		boolean hasClay = findHotbarItemByItem(client.player, Items.CLAY) != -1
				|| client.player.getOffHandStack().isOf(Items.CLAY)
				|| ShulkerIndex.countPlayerClayBlocks(client.player) > 0;
		if (hasClay) {
			debugChat(client, "§aStarting break cycle!");
			currentState = State.PLACING; subState = SubState.SCAN;
		} else if (shulkerIndex.hasRefillSources()) {
			debugChat(client, "§eNo clay in inventory, refilling...");
			currentState = State.REFILLING; subState = SubState.OPEN; openRetryCount = 0;
			ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextRefillSource();
			if (source != null) activeShulkerPos = source.pos;
		} else {
			stop(client, "No clay blocks available anywhere!");
		}
	}

	// =================================================================
	//  PLACING
	// =================================================================

	private void handlePlacing(MinecraftClient client) {
		SurvivalConfig cfg = SurvivalConfig.get();
		ClientPlayerEntity player = client.player;

		overridePitch(player, 45f);

		int shovelSlot = findHotbarItem(player, ItemTags.SHOVELS);
		if (shovelSlot == -1) { stop(client, "No shovel found in hotbar!"); return; }

		ItemStack offHandItem = player.getOffHandStack();
		if (!offHandItem.isOf(Items.CLAY)) {
			int claySlot = findHotbarItemByItem(player, Items.CLAY);
			if (claySlot == -1) {
				if (shulkerIndex.hasRefillSources()) {
					debugChat(client, "§eOut of clay blocks, refilling...");
					currentState = State.REFILLING; subState = SubState.OPEN; openRetryCount = 0;
					ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextRefillSource();
					if (source != null) { activeShulkerPos = source.pos; delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client); }
					else stop(client, "No clay sources left!");
				} else {
					returnStateAfterIndexing = State.PLACING;
					currentState = State.INDEXING; subState = SubState.SCAN;
				}
				return;
			}
			player.getInventory().setSelectedSlot(claySlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(claySlot));
			sendPacket(client, new PlayerActionC2SPacket(
					PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
			player.getInventory().setSelectedSlot(shovelSlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(shovelSlot));
			placingStallTicks = 0;
			delayTimer = 2;
			return;
		}

		player.getInventory().setSelectedSlot(shovelSlot);

		if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
			BlockHitResult hitResult = (BlockHitResult) client.crosshairTarget;
			targetPos = hitResult.getBlockPos().offset(hitResult.getSide());
			client.interactionManager.interactBlock(player, Hand.OFF_HAND, hitResult);
			player.swingHand(Hand.OFF_HAND);
			placingStallTicks = 0;
			delayTimer = SurvivalUtils.getAdaptedDelay(cfg.placeDelay, client);
			currentState = State.BREAKING;
		}
	}

	// =================================================================
	//  BREAKING
	// =================================================================

	private void handleBreaking(MinecraftClient client) {
		SurvivalConfig cfg = SurvivalConfig.get();
		ClientPlayerEntity player = client.player;

		if (targetPos == null || client.world.isAir(targetPos)) {
			if (targetPos != null) clayMinedCount++;
			delayTimer = SurvivalUtils.getAdaptedDelay(cfg.breakDelay, client);
			currentState = State.PLACING;
			return;
		}

		ItemStack mainHand = player.getMainHandStack();
		if (mainHand.isIn(ItemTags.SHOVELS)) {
			int remaining = mainHand.getMaxDamage() - mainHand.getDamage();
			if (remaining < cfg.durabilityRepairThreshold) { currentState = State.REPAIRING; return; }
		} else {
			int slot = findHotbarItem(player, ItemTags.SHOVELS);
			if (slot == -1) { stop(client, "No shovel found in hotbar!"); return; }
			player.getInventory().setSelectedSlot(slot);
			return;
		}

		client.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
		player.swingHand(Hand.MAIN_HAND);

		if (client.world.isAir(targetPos)) {
			clayMinedCount++;
			watchdogTicks = 0;
			breakingStallTicks = 0;
			delayTimer = SurvivalUtils.getAdaptedDelay(cfg.breakDelay, client);
			currentState = State.PLACING;
		}
	}

	// =================================================================
	//  REPAIRING
	// =================================================================

	private void handleRepairing(MinecraftClient client) {
		SurvivalConfig cfg = SurvivalConfig.get();
		ClientPlayerEntity player = client.player;

		int shovelSlot = findHotbarItem(player, ItemTags.SHOVELS);
		if (shovelSlot == -1) { stop(client, "No shovel found in hotbar!"); return; }
		player.getInventory().setSelectedSlot(shovelSlot);
		ItemStack shovelStack = player.getInventory().getStack(shovelSlot);

		if (!hasMending(shovelStack)) { stop(client, "Shovel has no Mending!"); return; }

		int remaining = shovelStack.getMaxDamage() - shovelStack.getDamage();
		if (remaining > cfg.durabilityRepairedTarget) { watchdogTicks = 0; currentState = State.PLACING; return; }

		ItemStack offHandItem = player.getOffHandStack();
		if (!offHandItem.isOf(Items.EXPERIENCE_BOTTLE)) {
			int xpSlot = findHotbarItemByItem(player, Items.EXPERIENCE_BOTTLE);
			if (xpSlot == -1) { stop(client, "Out of XP Bottles!"); return; }
			player.getInventory().setSelectedSlot(xpSlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(xpSlot));
			sendPacket(client, new PlayerActionC2SPacket(
					PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
			player.getInventory().setSelectedSlot(shovelSlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(shovelSlot));
			delayTimer = 2;
			return;
		}

		overridePitch(player, 90f);
		client.interactionManager.interactItem(player, Hand.OFF_HAND);
		player.swingHand(Hand.OFF_HAND);
		xpBottlesUsed++;
		delayTimer = SurvivalUtils.getAdaptedDelay(cfg.repairThrowDelay, client);
	}

	// =================================================================
	//  REFILLING
	// =================================================================

	private void handleRefilling(MinecraftClient client) {
		SurvivalConfig cfg = SurvivalConfig.get();
		switch (subState) {
			case OPEN -> {
				if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
					ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextRefillSource();
					if (next == null) { stop(client, "All clay source shulkers exhausted!"); return; }
					activeShulkerPos = next.pos;
					delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
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
						if (stack.isOf(Items.CLAY)) {
							client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
							transferred += stack.getCount();
						}
					}
					List<ItemStack> updated = new ArrayList<>();
					for (int i = 0; i < 27; i++)
						updated.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
					long tick = client.world != null ? client.world.getTime() : 0;
					shulkerIndex.indexShulker(activeShulkerPos, updated, tick);

					client.player.closeHandledScreen();
					if (transferred > 0) { debugChat(client, "§aRefilled " + transferred + " clay blocks."); shulkersEmptied++; }

					boolean hasClay = client.player.getOffHandStack().isOf(Items.CLAY) || findHotbarItemByItem(client.player, Items.CLAY) != -1;
					if (!hasClay) {
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextRefillSource();
						if (next == null) { stop(client, "No clay obtained and no sources left!"); return; }
						activeShulkerPos = next.pos;
						subState = SubState.OPEN;
					} else {
						watchdogTicks = 0;
						currentState = State.PLACING; subState = SubState.SCAN;
					}
					openRetryCount = 0;
					delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
				} else {
					openRetryCount++;
					if (openRetryCount > cfg.maxOpenRetries) {
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextRefillSource();
						if (next == null) { stop(client, "Can't open any source shulkers!"); return; }
						activeShulkerPos = next.pos; openRetryCount = 0;
					}
					subState = SubState.OPEN;
					delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
				}
			}
		}
	}

	// =================================================================
	//  DEPOSITING
	// =================================================================

	private void handleDepositing(MinecraftClient client) {
		SurvivalConfig cfg = SurvivalConfig.get();
		switch (subState) {
			case SCAN -> {
				shulkerIndex.scanNearbyShulkers(client.player, cfg.shulkerSearchRadius, cfg.acceptAnyShulkerColor);
				int clayBalls = ShulkerIndex.countPlayerClayBalls(client.player);
				if (clayBalls == 0) { currentState = State.PLACING; subState = SubState.SCAN; return; }
				debugChat(client, "§6Depositing " + clayBalls + " clay balls...");
				ShulkerIndex.ShulkerEntry target = shulkerIndex.pollNextDepositTarget();
				if (target == null) {
					List<ShulkerIndex.ShulkerEntry> unindexed = shulkerIndex.getUnindexedShulkers();
					if (!unindexed.isEmpty()) {
						returnStateAfterIndexing = State.DEPOSITING;
						currentState = State.INDEXING; subState = SubState.SCAN; return;
					}
					stop(client, "No shulkers with free space for deposit!"); return;
				}
				activeShulkerPos = target.pos;
				subState = SubState.OPEN;
				delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
			}
			case OPEN -> {
				if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
					ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextDepositTarget();
					if (next == null) { currentState = State.PLACING; subState = SubState.SCAN; return; }
					activeShulkerPos = next.pos;
					delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client); return;
				}
				openShulkerAt(client, activeShulkerPos);
				subState = SubState.TRANSFER; openRetryCount = 0;
				delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerTransferDelay, client);
			}
			case TRANSFER -> {
				if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
					int syncId = shulkerScreen.getScreenHandler().syncId;
					int deposited = 0;
					int totalSlots = shulkerScreen.getScreenHandler().slots.size();
					for (int i = 27; i < totalSlots; i++) {
						ItemStack stack = shulkerScreen.getScreenHandler().getSlot(i).getStack();
						if (stack.isOf(Items.CLAY_BALL)) {
							client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
							deposited += stack.getCount();
						}
					}
					List<ItemStack> updated = new ArrayList<>();
					for (int i = 0; i < 27; i++)
						updated.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
					long tick = client.world != null ? client.world.getTime() : 0;
					shulkerIndex.indexShulker(activeShulkerPos, updated, tick);
					client.player.closeHandledScreen();
					debugChat(client, "§aDeposited " + deposited + " clay balls.");

					int remaining = ShulkerIndex.countPlayerClayBalls(client.player);
					if (remaining > 0) {
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextDepositTarget();
						if (next != null) { activeShulkerPos = next.pos; subState = SubState.OPEN; }
						else { currentState = State.PLACING; subState = SubState.SCAN; }
					} else {
						currentState = State.PLACING; subState = SubState.SCAN; watchdogTicks = 0;
					}
					openRetryCount = 0;
					delayTimer = SurvivalUtils.getAdaptedDelay(cfg.shulkerOpenDelay, client);
				} else {
					openRetryCount++;
					if (openRetryCount > cfg.maxOpenRetries) {
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextDepositTarget();
						if (next == null) { currentState = State.PLACING; subState = SubState.SCAN; return; }
						activeShulkerPos = next.pos; openRetryCount = 0;
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

	private void saveCameraState(ClientPlayerEntity player) {
		if (player == null) return;
		savedPitch = player.getPitch();
		savedSlot = player.getInventory().getSelectedSlot();
		cameraOverridden = false;
	}

	private void overridePitch(ClientPlayerEntity player, float pitch) {
		player.setPitch(pitch);
		cameraOverridden = true;
	}

	private void restoreCameraState(ClientPlayerEntity player) {
		if (player == null) return;
		if (cameraOverridden) { player.setPitch(savedPitch); cameraOverridden = false; }
		player.getInventory().setSelectedSlot(savedSlot);
	}

	private static int findHotbarItem(ClientPlayerEntity player, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
		for (int i = 0; i < 9; i++) if (player.getInventory().getStack(i).isIn(tag)) return i;
		return -1;
	}

	private static int findHotbarItemByItem(ClientPlayerEntity player, net.minecraft.item.Item item) {
		for (int i = 0; i < 9; i++) if (player.getInventory().getStack(i).isOf(item)) return i;
		return -1;
	}

	private static boolean hasMending(ItemStack stack) {
		// FIX #12: Check both possible formats
		var enchantments = stack.getEnchantments();
		for (var entry : enchantments.getEnchantmentEntries()) {
			String id = entry.getKey().getIdAsString();
			if (id.equals("minecraft:mending") || id.endsWith(":mending") || id.equals("mending"))
				return true;
		}
		return false;
	}

	private static boolean isInventoryNearlyFull(ClientPlayerEntity player) {
		int free = 0;
		for (int i = 0; i < 36; i++) if (player.getInventory().getStack(i).isEmpty()) free++;
		return free <= 2;
	}

	private static void sendPacket(MinecraftClient client, net.minecraft.network.packet.Packet<?> packet) {
		if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(packet);
	}

	private void debugChat(MinecraftClient client, String message) {
		if (client.player != null)
			client.player.sendMessage(Text.literal("§8[§6ClayBot§8] §r" + message), false);
	}
}