package jjhenus.clayfarmer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.enchantment.Enchantment;
import java.util.ArrayList;
import java.util.List;

public class ClayfarmerClient implements ClientModInitializer {

	private enum State {
		IDLE, INDEXING, PLACING, BREAKING, REPAIRING, REFILLING, DEPOSITING
	}

	private enum SubState {
		SCAN, OPEN, READ, TRANSFER, DONE
	}

	private static State currentState = State.IDLE;
	private static SubState subState = SubState.SCAN;

	private static BlockPos targetPos = null;
	private static BlockPos activeShulkerPos = null;
	private static int delayTimer = 0;

	private static State lastWatchdogState = State.IDLE;
	private static int watchdogTicks = 0;

	private static float savedPitch = 0f;
	private static int savedSlot = 0;
	private static boolean cameraOverridden = false;

	private static int openRetryCount = 0;
	private static final int MAX_OPEN_RETRIES = 10;

	private static List<ShulkerIndex.ShulkerEntry> indexingQueue = new ArrayList<>();
	private static int indexingCursor = 0;
	private static State returnStateAfterIndexing = State.PLACING;

	private static int depositStartBalls = 0;

	private static int debugChatCooldown = 0;
	private static final int DEBUG_CHAT_INTERVAL = 100;

	private static boolean mouseUngrabbed = false;
	private static boolean savedPauseOnLostFocus = true;

	private static int breakingStallTicks = 0;
	private static int placingStallTicks = 0;

	private static final ShulkerIndex shulkerIndex = new ShulkerIndex();

	private static int clayMinedCount = 0;
	private static int shulkersEmptied = 0;
	private static int xpBottlesUsed = 0;
	private static long sessionStartTick = 0;
	private static long totalSessionTicks = 0;

	@Override
	public void onInitializeClient() {
		ClayfarmerConfig.load();
		registerCommands();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
			if (currentState != State.IDLE) {
				renderHud(drawContext);
			}
		});
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("claybot")

					.then(ClientCommandManager.literal("start").executes(context -> {
						MinecraftClient client = MinecraftClient.getInstance();
						if (client.player == null) return 0;

						List<String> missing = preflightChecks(client.player);
						if (!missing.isEmpty()) {
							context.getSource().sendFeedback(Text.literal("\u00a7c\u00a7lClayBot Pre-Flight Failed:"));
							for (String m : missing) {
								context.getSource().sendFeedback(Text.literal("  \u00a7c\u2717 " + m));
							}
							return 0;
						}

						saveCameraState(client.player);
						mouseUngrabbed = true;

						savedPauseOnLostFocus = client.options.pauseOnLostFocus;
						client.options.pauseOnLostFocus = false;

						breakingStallTicks = 0;
						placingStallTicks = 0;
						sessionStartTick = client.world != null ? client.world.getTime() : 0;
						totalSessionTicks = 0;
						watchdogTicks = 0;
						openRetryCount = 0;
						shulkerIndex.clear();

						returnStateAfterIndexing = State.PLACING;
						currentState = State.INDEXING;
						subState = SubState.SCAN;

						debugChat(client, "\u00a7a\u00a7lStarted! \u00a7rBeginning shulker index scan...");
						return 1;
					}))

					.then(ClientCommandManager.literal("stop").executes(context -> {
						stopBot(MinecraftClient.getInstance(), "Manual stop.");
						return 1;
					}))

					.then(ClientCommandManager.literal("reset").executes(context -> {
						clayMinedCount = 0;
						shulkersEmptied = 0;
						xpBottlesUsed = 0;
						totalSessionTicks = 0;
						shulkerIndex.clear();
						context.getSource().sendFeedback(Text.literal("\u00a7bClayBot: All stats reset!"));
						return 1;
					}))

					.then(ClientCommandManager.literal("stats").executes(context -> {
						MinecraftClient c = MinecraftClient.getInstance();
						long uptimeTicks = totalSessionTicks;
						if (currentState != State.IDLE && c.world != null) {
							uptimeTicks += c.world.getTime() - sessionStartTick;
						}
						long uptimeSec = Math.max(uptimeTicks / 20, 1);
						long mins = uptimeSec / 60;
						long secs = uptimeSec % 60;
						double clayPerHour = (clayMinedCount / (double) uptimeSec) * 3600;

						context.getSource().sendFeedback(Text.literal("\u00a76\u00a7l== ClayBot Stats =="));
						context.getSource().sendFeedback(Text.literal("\u00a7f Clay Mined:      \u00a7a" + clayMinedCount));
						context.getSource().sendFeedback(Text.literal("\u00a7f Shulkers Emptied: \u00a7a" + shulkersEmptied));
						context.getSource().sendFeedback(Text.literal("\u00a7f XP Bottles Used:  \u00a7a" + xpBottlesUsed));
						context.getSource().sendFeedback(Text.literal("\u00a7f Uptime:           \u00a7a" + mins + "m " + secs + "s"));
						context.getSource().sendFeedback(Text.literal("\u00a7f Clay/Hour:        \u00a7a" + String.format("%.0f", clayPerHour)));
						return 1;
					}))

					.then(ClientCommandManager.literal("inventory").executes(context -> {
						MinecraftClient c = MinecraftClient.getInstance();
						if (c.player == null) return 0;
						printInventoryReport(c);
						return 1;
					}))

					.then(ClientCommandManager.literal("reload").executes(context -> {
						ClayfarmerConfig.reload();
						context.getSource().sendFeedback(Text.literal("\u00a7bClayBot: Config reloaded."));
						return 1;
					}))

					.then(ClientCommandManager.literal("ungrab").executes(context -> {
						mouseUngrabbed = true;
						MinecraftClient.getInstance().mouse.unlockCursor();
						context.getSource().sendFeedback(Text.literal("\u00a7bClayBot: Mouse released."));
						return 1;
					}))

					.then(ClientCommandManager.literal("grab").executes(context -> {
						mouseUngrabbed = false;
						MinecraftClient.getInstance().mouse.lockCursor();
						context.getSource().sendFeedback(Text.literal("\u00a7bClayBot: Mouse locked."));
						return 1;
					}))
			);
		});
	}

	private List<String> preflightChecks(ClientPlayerEntity player) {
		List<String> problems = new ArrayList<>();

		int shovelSlot = findHotbarItem(player, ItemTags.SHOVELS);
		if (shovelSlot == -1) {
			problems.add("No shovel in hotbar");
		} else {
			ItemStack shovel = player.getInventory().getStack(shovelSlot);
			if (!hasMending(shovel)) {
				problems.add("Shovel does not have Mending");
			}
		}

		boolean hasClay = player.getOffHandStack().isOf(Items.CLAY)
				|| findHotbarItemByItem(player, Items.CLAY) != -1;
		if (!hasClay) {
			problems.add("No clay blocks in hotbar or offhand");
		}

		if (findHotbarItemByItem(player, Items.EXPERIENCE_BOTTLE) == -1) {
			problems.add("No XP bottles in hotbar");
		}

		return problems;
	}

	// =====================================================================
	//  MAIN TICK LOOP
	// =====================================================================

	private void onTick(MinecraftClient client) {
		if (client.player == null || client.interactionManager == null || currentState == State.IDLE) return;

		// Keep cursor unlocked every tick
		if (mouseUngrabbed && client.mouse.isCursorLocked()) {
			client.mouse.unlockCursor();
		}

		// Screen correction: close unwanted GUIs (game menu, etc.)
		// Must run even during delays so the bot never stalls.
		// Shulker screens are allowed when we expect them.
		if (client.currentScreen != null) {
			boolean isShulkerScreen = client.currentScreen instanceof ShulkerBoxScreen;

			if (!isShulkerScreen) {
				// Game menu, inventory, chat, death screen, etc.
				client.setScreen(null);
				delayTimer = 3;
				return;
			}

			// Shulker screen: allow during OPEN (waiting for it) and READ/TRANSFER
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

		// Delay timer
		if (delayTimer > 0) {
			delayTimer--;
			watchdogTicks++;
			return;
		}

		// === Everything below only runs when delayTimer == 0 ===

		if (debugChatCooldown > 0) debugChatCooldown--;

		ClayfarmerConfig cfg = ClayfarmerConfig.get();

		// Watchdog
		if (currentState != lastWatchdogState) {
			lastWatchdogState = currentState;
			watchdogTicks = 0;
		} else {
			watchdogTicks++;
		}
		if (watchdogTicks > cfg.stateTimeoutTicks) {
			stopBot(client, "Watchdog timeout in " + currentState + "/" + subState + " (" + watchdogTicks + " ticks)");
			return;
		}

		// Self-correction: stalled BREAKING
		if (currentState == State.BREAKING) {
			if (targetPos != null && !client.world.isAir(targetPos)) {
				breakingStallTicks++;
				if (breakingStallTicks > 60) {
					debugChat(client, "\u00a7e[correction] Breaking stalled, resetting to PLACING...");
					breakingStallTicks = 0;
					targetPos = null;
					currentState = State.PLACING;
					delayTimer = 5;
					return;
				}
			} else {
				breakingStallTicks = 0;
			}
		} else {
			breakingStallTicks = 0;
		}

		// Self-correction: stalled PLACING
		if (currentState == State.PLACING) {
			placingStallTicks++;
			if (placingStallTicks > 40) {
				debugChat(client, "\u00a7e[correction] Placing stalled, re-adjusting...");
				placingStallTicks = 0;
				client.player.setPitch(45f);
				delayTimer = 5;
				return;
			}
		} else {
			placingStallTicks = 0;
		}

		// Periodic debug
		if (debugChatCooldown == 0 && currentState != State.INDEXING) {
			printPeriodicDebug(client);
			debugChatCooldown = DEBUG_CHAT_INTERVAL;
		}

		// Inventory full -> deposit
		if (currentState == State.PLACING || currentState == State.BREAKING) {
			if (isInventoryNearlyFull(client.player)) {
				debugChat(client, "\u00a76Inventory nearly full, depositing...");
				depositStartBalls = ShulkerIndex.countPlayerClayBalls(client.player);
				currentState = State.DEPOSITING;
				subState = SubState.SCAN;
				return;
			}
		}

		// State dispatch
		switch (currentState) {
			case INDEXING   -> handleIndexing(client);
			case PLACING    -> handlePlacing(client);
			case BREAKING   -> handleBreaking(client);
			case REPAIRING  -> handleRepairing(client);
			case REFILLING  -> handleRefilling(client);
			case DEPOSITING -> handleDepositing(client);
			default -> {}
		}
	}

	// =====================================================================
	//  INDEXING
	// =====================================================================

	private void handleIndexing(MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();

		switch (subState) {

			case SCAN -> {
				shulkerIndex.scanNearbyShulkers(client.player, cfg.shulkerSearchRadius, cfg.acceptAnyShulkerColor);
				indexingQueue = new ArrayList<>(shulkerIndex.getUnindexedShulkers());
				indexingCursor = 0;

				if (indexingQueue.isEmpty()) {
					if (shulkerIndex.getTotalCount() == 0) {
						debugChat(client, "\u00a7eNo shulker boxes found nearby.");
					} else {
						debugChat(client, "\u00a7aAll " + shulkerIndex.getIndexedCount() + " shulkers already indexed.");
					}
					subState = SubState.DONE;
					return;
				}

				debugChat(client, "\u00a7bFound " + indexingQueue.size() + " shulker(s) to index...");
				subState = SubState.OPEN;
				delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
			}

			case OPEN -> {
				if (indexingCursor >= indexingQueue.size()) {
					subState = SubState.DONE;
					return;
				}

				ShulkerIndex.ShulkerEntry entry = indexingQueue.get(indexingCursor);
				activeShulkerPos = entry.pos;

				if (client.world.isAir(activeShulkerPos)) {
					indexingCursor++;
					return;
				}

				BlockHitResult hit = new BlockHitResult(
						activeShulkerPos.toCenterPos(), Direction.UP, activeShulkerPos, false
				);
				client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
				client.player.swingHand(Hand.MAIN_HAND);

				subState = SubState.READ;
				openRetryCount = 0;
				delayTimer = adaptDelay(cfg.shulkerTransferDelay, client);
			}

			case READ -> {
				if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
					List<ItemStack> slots = new ArrayList<>();
					for (int i = 0; i < 27; i++) {
						slots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
					}

					long tick = client.world != null ? client.world.getTime() : 0;
					shulkerIndex.indexShulker(activeShulkerPos, slots, tick);

					ShulkerIndex.ShulkerEntry entry = null;
					for (ShulkerIndex.ShulkerEntry e : shulkerIndex.getAllShulkers()) {
						if (e.pos.equals(activeShulkerPos)) { entry = e; break; }
					}

					if (entry != null) {
						debugChat(client, "\u00a77  Indexed \u00a7f" + activeShulkerPos.toShortString()
								+ "\u00a77: \u00a7a" + entry.clayBlockCount + " blocks\u00a77, \u00a7b"
								+ entry.clayBallCount + " balls\u00a77, \u00a7e" + entry.freeSlots() + " free"
								+ " \u00a77[" + entry.role + "]");
					}

					client.player.closeHandledScreen();
					indexingCursor++;
					openRetryCount = 0;

					if (indexingCursor < indexingQueue.size()) {
						subState = SubState.OPEN;
						delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
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
					delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
				}
			}

			case DONE -> finishIndexing(client);
		}
	}

	private void finishIndexing(MinecraftClient client) {
		printInventoryReport(client);

		if (returnStateAfterIndexing == State.DEPOSITING) {
			currentState = State.DEPOSITING;
			subState = SubState.SCAN;
			return;
		}

		boolean hasClayInHotbar = findHotbarItemByItem(client.player, Items.CLAY) != -1
				|| client.player.getOffHandStack().isOf(Items.CLAY);

		if (hasClayInHotbar || ShulkerIndex.countPlayerClayBlocks(client.player) > 0) {
			debugChat(client, "\u00a7aStarting break cycle!");
			currentState = State.PLACING;
			subState = SubState.SCAN;
		} else if (shulkerIndex.hasRefillSources()) {
			debugChat(client, "\u00a7eNo clay in inventory, refilling...");
			currentState = State.REFILLING;
			subState = SubState.OPEN;
			openRetryCount = 0;
			ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextRefillSource();
			if (source != null) activeShulkerPos = source.pos;
		} else {
			stopBot(client, "No clay blocks available anywhere!");
		}
	}

	// =====================================================================
	//  PLACING
	// =====================================================================

	private void handlePlacing(MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();
		ClientPlayerEntity player = client.player;

		overridePitch(player, 45f);

		int shovelSlot = findHotbarItem(player, ItemTags.SHOVELS);
		if (shovelSlot == -1) {
			stopBot(client, "No shovel found in hotbar!");
			return;
		}

		ItemStack offHandItem = player.getOffHandStack();
		if (!offHandItem.isOf(Items.CLAY)) {
			int claySlot = findHotbarItemByItem(player, Items.CLAY);
			if (claySlot == -1) {
				if (shulkerIndex.hasRefillSources()) {
					debugChat(client, "\u00a7eOut of clay blocks, refilling...");
					currentState = State.REFILLING;
					subState = SubState.OPEN;
					openRetryCount = 0;
					ShulkerIndex.ShulkerEntry source = shulkerIndex.pollNextRefillSource();
					if (source != null) {
						activeShulkerPos = source.pos;
						delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
					} else {
						stopBot(client, "No clay sources left!");
					}
				} else {
					debugChat(client, "\u00a7cNo clay anywhere. Re-indexing...");
					returnStateAfterIndexing = State.PLACING;
					currentState = State.INDEXING;
					subState = SubState.SCAN;
				}
				return;
			}

			// Swap clay into offhand: tell server slot first, then swap, then re-select shovel
			player.getInventory().setSelectedSlot(claySlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(claySlot));
			sendPacket(client, new PlayerActionC2SPacket(
					PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
					BlockPos.ORIGIN, Direction.DOWN
			));
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
			delayTimer = adaptDelay(cfg.placeDelay, client);
			currentState = State.BREAKING;
		}
	}

	// =====================================================================
	//  BREAKING
	// =====================================================================

	private void handleBreaking(MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();
		ClientPlayerEntity player = client.player;

		if (targetPos == null || client.world.isAir(targetPos)) {
			if (targetPos != null) clayMinedCount++;
			delayTimer = adaptDelay(cfg.breakDelay, client);
			currentState = State.PLACING;
			return;
		}

		ItemStack mainHand = player.getMainHandStack();
		if (mainHand.isIn(ItemTags.SHOVELS)) {
			int remaining = mainHand.getMaxDamage() - mainHand.getDamage();
			if (remaining < cfg.durabilityRepairThreshold) {
				currentState = State.REPAIRING;
				return;
			}
		} else {
			int slot = findHotbarItem(player, ItemTags.SHOVELS);
			if (slot == -1) {
				stopBot(client, "No shovel found in hotbar!");
				return;
			}
			player.getInventory().setSelectedSlot(slot);
			return;
		}

		client.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
		player.swingHand(Hand.MAIN_HAND);

		if (client.world.isAir(targetPos)) {
			clayMinedCount++;
			watchdogTicks = 0;
			breakingStallTicks = 0;
			delayTimer = adaptDelay(cfg.breakDelay, client);
			currentState = State.PLACING;
		}
	}

	// =====================================================================
	//  REPAIRING
	// =====================================================================

	private void handleRepairing(MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();
		ClientPlayerEntity player = client.player;

		int shovelSlot = findHotbarItem(player, ItemTags.SHOVELS);
		if (shovelSlot == -1) {
			stopBot(client, "No shovel found in hotbar!");
			return;
		}
		player.getInventory().setSelectedSlot(shovelSlot);
		ItemStack shovelStack = player.getInventory().getStack(shovelSlot);

		if (!hasMending(shovelStack)) {
			stopBot(client, "Shovel has no Mending enchantment!");
			return;
		}

		int remaining = shovelStack.getMaxDamage() - shovelStack.getDamage();
		if (remaining > cfg.durabilityRepairedTarget) {
			watchdogTicks = 0;
			currentState = State.PLACING;
			return;
		}

		ItemStack offHandItem = player.getOffHandStack();
		if (!offHandItem.isOf(Items.EXPERIENCE_BOTTLE)) {
			int xpSlot = findHotbarItemByItem(player, Items.EXPERIENCE_BOTTLE);
			if (xpSlot == -1) {
				stopBot(client, "Out of XP Bottles!");
				return;
			}

			player.getInventory().setSelectedSlot(xpSlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(xpSlot));
			sendPacket(client, new PlayerActionC2SPacket(
					PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
					BlockPos.ORIGIN, Direction.DOWN
			));
			player.getInventory().setSelectedSlot(shovelSlot);
			sendPacket(client, new UpdateSelectedSlotC2SPacket(shovelSlot));
			delayTimer = 2;
			return;
		}

		overridePitch(player, 90f);
		client.interactionManager.interactItem(player, Hand.OFF_HAND);
		player.swingHand(Hand.OFF_HAND);
		xpBottlesUsed++;
		delayTimer = adaptDelay(cfg.repairThrowDelay, client);
	}

	// =====================================================================
	//  REFILLING
	// =====================================================================

	private void handleRefilling(MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();

		switch (subState) {

			case OPEN -> {
				if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
					ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextRefillSource();
					if (next == null) {
						stopBot(client, "All clay source shulkers exhausted!");
						return;
					}
					activeShulkerPos = next.pos;
					delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
					return;
				}

				BlockHitResult hit = new BlockHitResult(
						activeShulkerPos.toCenterPos(), Direction.UP, activeShulkerPos, false
				);
				client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
				client.player.swingHand(Hand.MAIN_HAND);

				subState = SubState.TRANSFER;
				openRetryCount = 0;
				delayTimer = adaptDelay(cfg.shulkerTransferDelay, client);
			}

			case TRANSFER -> {
				if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
					int syncId = shulkerScreen.getScreenHandler().syncId;
					boolean foundClay = false;
					int transferred = 0;

					for (int slotId = 0; slotId < 27; slotId++) {
						ItemStack slotStack = shulkerScreen.getScreenHandler().getSlot(slotId).getStack();
						if (slotStack.isOf(Items.CLAY)) {
							client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
							transferred += slotStack.getCount();
							foundClay = true;
						}
					}

					// Move clay from main inv (27-53) to hotbar (54-62)
					for (int mainSlot = 27; mainSlot < 54; mainSlot++) {
						ItemStack stack = shulkerScreen.getScreenHandler().getSlot(mainSlot).getStack();
						if (stack.isOf(Items.CLAY)) {
							for (int hotbarSlot = 54; hotbarSlot < 63; hotbarSlot++) {
								if (shulkerScreen.getScreenHandler().getSlot(hotbarSlot).getStack().isEmpty()) {
									client.interactionManager.clickSlot(syncId, mainSlot, 0, SlotActionType.PICKUP, client.player);
									client.interactionManager.clickSlot(syncId, hotbarSlot, 0, SlotActionType.PICKUP, client.player);
									break;
								}
							}
						}
					}

					List<ItemStack> updatedSlots = new ArrayList<>();
					for (int i = 0; i < 27; i++) {
						updatedSlots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
					}
					long tick = client.world != null ? client.world.getTime() : 0;
					shulkerIndex.indexShulker(activeShulkerPos, updatedSlots, tick);

					client.player.closeHandledScreen();

					if (foundClay) {
						debugChat(client, "\u00a7aRefilled \u00a7f" + transferred + "\u00a7a clay blocks from shulker");
						shulkersEmptied++;
					}

					boolean hasClay = client.player.getOffHandStack().isOf(Items.CLAY)
							|| findHotbarItemByItem(client.player, Items.CLAY) != -1;

					if (!hasClay) {
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextRefillSource();
						if (next == null) {
							stopBot(client, "No clay obtained and no sources left!");
							return;
						}
						activeShulkerPos = next.pos;
						subState = SubState.OPEN;
						delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
					} else {
						watchdogTicks = 0;
						currentState = State.PLACING;
						subState = SubState.SCAN;
						delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
					}
					openRetryCount = 0;
				} else {
					openRetryCount++;
					if (openRetryCount > MAX_OPEN_RETRIES) {
						debugChat(client, "\u00a7cFailed to open refill shulker, trying next...");
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextRefillSource();
						if (next == null) {
							stopBot(client, "Can't open any source shulkers!");
							return;
						}
						activeShulkerPos = next.pos;
						openRetryCount = 0;
					}
					subState = SubState.OPEN;
					delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
				}
			}
		}
	}

	// =====================================================================
	//  DEPOSITING
	// =====================================================================

	private void handleDepositing(MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();

		switch (subState) {

			case SCAN -> {
				shulkerIndex.scanNearbyShulkers(client.player, cfg.shulkerSearchRadius, cfg.acceptAnyShulkerColor);

				int clayBalls = ShulkerIndex.countPlayerClayBalls(client.player);
				if (clayBalls == 0) {
					debugChat(client, "\u00a7aNo clay balls to deposit. Resuming.");
					currentState = State.PLACING;
					subState = SubState.SCAN;
					return;
				}

				debugChat(client, "\u00a76Depositing \u00a7f" + clayBalls + "\u00a76 clay balls...");

				ShulkerIndex.ShulkerEntry target = shulkerIndex.pollNextDepositTarget();
				if (target == null) {
					List<ShulkerIndex.ShulkerEntry> unindexed = shulkerIndex.getUnindexedShulkers();
					if (!unindexed.isEmpty()) {
						debugChat(client, "\u00a7eFound unindexed shulkers, scanning first...");
						returnStateAfterIndexing = State.DEPOSITING;
						currentState = State.INDEXING;
						subState = SubState.SCAN;
						return;
					}
					stopBot(client, "No shulkers with free space for deposit!");
					return;
				}

				activeShulkerPos = target.pos;
				subState = SubState.OPEN;
				delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
			}

			case OPEN -> {
				if (activeShulkerPos == null || client.world.isAir(activeShulkerPos)) {
					ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextDepositTarget();
					if (next == null) {
						int leftover = ShulkerIndex.countPlayerClayBalls(client.player);
						if (leftover > 0) {
							debugChat(client, "\u00a7cNo more deposit targets! " + leftover + " balls stuck.");
						}
						currentState = State.PLACING;
						subState = SubState.SCAN;
						return;
					}
					activeShulkerPos = next.pos;
					delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
					return;
				}

				BlockHitResult hit = new BlockHitResult(
						activeShulkerPos.toCenterPos(), Direction.UP, activeShulkerPos, false
				);
				client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
				client.player.swingHand(Hand.MAIN_HAND);

				subState = SubState.TRANSFER;
				openRetryCount = 0;
				delayTimer = adaptDelay(cfg.shulkerTransferDelay, client);
			}

			case TRANSFER -> {
				if (client.currentScreen instanceof ShulkerBoxScreen shulkerScreen) {
					int syncId = shulkerScreen.getScreenHandler().syncId;
					int deposited = 0;

					int totalSlots = shulkerScreen.getScreenHandler().slots.size();
					for (int slotId = 27; slotId < totalSlots; slotId++) {
						ItemStack slotStack = shulkerScreen.getScreenHandler().getSlot(slotId).getStack();
						if (slotStack.isOf(Items.CLAY_BALL)) {
							client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
							deposited += slotStack.getCount();
						}
					}

					List<ItemStack> updatedSlots = new ArrayList<>();
					for (int i = 0; i < 27; i++) {
						updatedSlots.add(shulkerScreen.getScreenHandler().getSlot(i).getStack().copy());
					}
					long tick = client.world != null ? client.world.getTime() : 0;
					shulkerIndex.indexShulker(activeShulkerPos, updatedSlots, tick);

					client.player.closeHandledScreen();

					debugChat(client, "\u00a7aDeposited \u00a7f" + deposited + "\u00a7a clay balls into shulker");

					int remaining = ShulkerIndex.countPlayerClayBalls(client.player);
					if (remaining > 0) {
						debugChat(client, "\u00a7e" + remaining + " clay balls remaining...");
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextDepositTarget();
						if (next != null) {
							activeShulkerPos = next.pos;
							subState = SubState.OPEN;
							delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
						} else {
							debugChat(client, "\u00a7cNo more deposit targets! " + remaining + " balls stuck.");
							currentState = State.PLACING;
							subState = SubState.SCAN;
							delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
						}
					} else {
						debugChat(client, "\u00a7a\u00a7lDeposit complete! Resuming break cycle.");
						printInventoryReport(client);
						currentState = State.PLACING;
						subState = SubState.SCAN;
						watchdogTicks = 0;
						delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
					}
					openRetryCount = 0;
				} else {
					openRetryCount++;
					if (openRetryCount > MAX_OPEN_RETRIES) {
						debugChat(client, "\u00a7cFailed to open deposit shulker, trying next...");
						ShulkerIndex.ShulkerEntry next = shulkerIndex.pollNextDepositTarget();
						if (next == null) {
							currentState = State.PLACING;
							subState = SubState.SCAN;
							return;
						}
						activeShulkerPos = next.pos;
						openRetryCount = 0;
					}
					subState = SubState.OPEN;
					delayTimer = adaptDelay(cfg.shulkerOpenDelay, client);
				}
			}
		}
	}

	// =====================================================================
	//  HUD
	// =====================================================================

	private void renderHud(net.minecraft.client.gui.DrawContext drawContext) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;

		ClayfarmerConfig cfg = ClayfarmerConfig.get();
		int x = cfg.hudX;
		int y = cfg.hudY;
		int color = cfg.hudColor;
		boolean shadow = cfg.hudShadow;
		int lineH = 11;

		String stateStr = switch (currentState) {
			case INDEXING   -> "\u00a79Indexing (" + indexingCursor + "/" + indexingQueue.size() + ")";
			case PLACING    -> "\u00a7aPlacing";
			case BREAKING   -> "\u00a7eBreaking";
			case REPAIRING  -> "\u00a7dRepairing";
			case REFILLING  -> "\u00a76Refilling";
			case DEPOSITING -> "\u00a7bDepositing";
			default         -> "\u00a77Idle";
		};
		drawContext.drawText(client.textRenderer, "\u00a7f\u00a7lClayBot \u00a7r" + stateStr, x, y, color, shadow);
		y += lineH;

		drawContext.drawText(client.textRenderer, "\u00a7f Clay Mined: \u00a7a" + clayMinedCount, x, y, color, shadow);
		y += lineH;

		long uptimeTicks = totalSessionTicks;
		if (client.world != null) uptimeTicks += client.world.getTime() - sessionStartTick;
		long uptimeSec = Math.max(uptimeTicks / 20, 1);
		double clayPerHour = (clayMinedCount / (double) uptimeSec) * 3600;
		drawContext.drawText(client.textRenderer, "\u00a7f Rate: \u00a7b" + String.format("%.0f", clayPerHour) + "/hr", x, y, color, shadow);
		y += lineH;

		int playerBlocks = ShulkerIndex.countPlayerClayBlocks(client.player);
		int shulkerBlocks = shulkerIndex.getTotalShulkerClayBlocks();
		drawContext.drawText(client.textRenderer,
				"\u00a7f Blocks: \u00a7a" + playerBlocks + " \u00a77inv + \u00a7a" + shulkerBlocks + " \u00a77shulk = \u00a7a\u00a7l" + (playerBlocks + shulkerBlocks),
				x, y, color, shadow);
		y += lineH;

		int playerBalls = ShulkerIndex.countPlayerClayBalls(client.player);
		int shulkerBalls = shulkerIndex.getTotalShulkerClayBalls();
		drawContext.drawText(client.textRenderer,
				"\u00a7f Balls:  \u00a7b" + playerBalls + " \u00a77inv + \u00a7b" + shulkerBalls + " \u00a77shulk = \u00a7b\u00a7l" + (playerBalls + shulkerBalls),
				x, y, color, shadow);
		y += lineH;

		int shovelSlot = findHotbarItem(client.player, ItemTags.SHOVELS);
		if (shovelSlot != -1) {
			ItemStack shovel = client.player.getInventory().getStack(shovelSlot);
			int dur = shovel.getMaxDamage() - shovel.getDamage();
			String durColor = dur < cfg.durabilityRepairThreshold ? "\u00a7c"
					: dur < cfg.durabilityRepairedTarget ? "\u00a7e" : "\u00a7a";
			drawContext.drawText(client.textRenderer, "\u00a7f Shovel: " + durColor + dur + "/" + shovel.getMaxDamage(),
					x, y, color, shadow);
			y += lineH;
		}

		int xpCount = countItemInHotbar(client.player, Items.EXPERIENCE_BOTTLE);
		drawContext.drawText(client.textRenderer, "\u00a7f XP: \u00a7d" + xpCount, x, y, color, shadow);
		y += lineH;

		int depositSlots = shulkerIndex.getTotalFreeDepositSlots();
		drawContext.drawText(client.textRenderer,
				"\u00a7f Shulkers: \u00a76" + shulkerIndex.getIndexedCount() + "/" + shulkerIndex.getTotalCount()
						+ " \u00a77| \u00a7e" + depositSlots + " deposit slots",
				x, y, color, shadow);
	}

	// =====================================================================
	//  Debug Chat
	// =====================================================================

	private void debugChat(MinecraftClient client, String message) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("\u00a78[\u00a76ClayBot\u00a78] \u00a7r" + message), false);
		}
	}

	private void printPeriodicDebug(MinecraftClient client) {
		if (client.player == null) return;
		int pB = ShulkerIndex.countPlayerClayBlocks(client.player);
		int pBa = ShulkerIndex.countPlayerClayBalls(client.player);
		int sB = shulkerIndex.getTotalShulkerClayBlocks();
		int sBa = shulkerIndex.getTotalShulkerClayBalls();
		int free = shulkerIndex.getTotalFreeDepositSlots();

		debugChat(client, "\u00a77[debug] \u00a7aBlocks:" + (pB + sB)
				+ " \u00a77(inv:" + pB + " shulk:" + sB + ")"
				+ " \u00a7bBalls:" + (pBa + sBa)
				+ " \u00a77(inv:" + pBa + " shulk:" + sBa + ")"
				+ " \u00a7eDeposit:" + free);
	}

	private void printInventoryReport(MinecraftClient client) {
		if (client.player == null) return;
		int pB = ShulkerIndex.countPlayerClayBlocks(client.player);
		int pBa = ShulkerIndex.countPlayerClayBalls(client.player);
		int sB = shulkerIndex.getTotalShulkerClayBlocks();
		int sBa = shulkerIndex.getTotalShulkerClayBalls();
		int free = shulkerIndex.getTotalFreeDepositSlots();

		debugChat(client, "\u00a76\u00a7l== Inventory Report ==");
		debugChat(client, "\u00a7f Shulkers: \u00a7a" + shulkerIndex.getIndexedCount() + "\u00a7f/" + shulkerIndex.getTotalCount());
		debugChat(client, "\u00a7f Blocks: \u00a7a" + pB + " inv + " + sB + " shulk = \u00a7a\u00a7l" + (pB + sB));
		debugChat(client, "\u00a7f Balls:  \u00a7b" + pBa + " inv + " + sBa + " shulk = \u00a7b\u00a7l" + (pBa + sBa));
		debugChat(client, "\u00a7f Deposit: \u00a7e" + free + " free slots");

		for (ShulkerIndex.ShulkerEntry entry : shulkerIndex.getAllShulkers()) {
			if (!entry.indexed) continue;
			String roleTag = switch (entry.role) {
				case CLAY_SOURCE      -> "§a[SRC]";
				case CLAY_BALL_SOURCE -> "§b[BAL]";
				case DEPOSIT_TARGET   -> "§e[DEP]";
				case EMERALD_DEPOSIT  -> "§2[EM]";
				case OTHER            -> "§7[---]";
			};
			debugChat(client, "\u00a77  " + roleTag + " \u00a7f" + entry.pos.toShortString()
					+ " \u00a7a" + entry.clayBlockCount + "b \u00a7b" + entry.clayBallCount + "b \u00a7e" + entry.freeSlots() + "f");
		}
	}

	// =====================================================================
	//  Stop Bot
	// =====================================================================

	private void stopBot(MinecraftClient client, String reason) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();

		if (client.world != null && sessionStartTick > 0) {
			totalSessionTicks += client.world.getTime() - sessionStartTick;
		}

		if (client.currentScreen != null && client.player != null) {
			client.player.closeHandledScreen();
		}

		currentState = State.IDLE;
		subState = SubState.SCAN;
		delayTimer = 0;
		watchdogTicks = 0;

		restoreCameraState(client.player);
		mouseUngrabbed = false;
		client.mouse.lockCursor();
		client.options.pauseOnLostFocus = savedPauseOnLostFocus;

		if (cfg.alertChatOnStop && client.player != null) {
			debugChat(client, "\u00a7c\u00a7lStopped: \u00a7r\u00a7c" + reason);
			printInventoryReport(client);
		}
		if (cfg.alertSoundOnStop && client.player != null) {
			client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
		}
	}

	// =====================================================================
	//  Camera
	// =====================================================================

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
		if (cameraOverridden) {
			player.setPitch(savedPitch);
			cameraOverridden = false;
		}
		player.getInventory().setSelectedSlot(savedSlot);
	}

	// =====================================================================
	//  Adaptive Delay
	// =====================================================================

	private int adaptDelay(int baseTicks, MinecraftClient client) {
		ClayfarmerConfig cfg = ClayfarmerConfig.get();
		if (cfg.adaptiveDelayPerPing <= 0 || client.getNetworkHandler() == null) return baseTicks;

		var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
		if (entry == null) return baseTicks;

		int pingMs = entry.getLatency();
		int extra = (int) Math.ceil((pingMs / 50.0) * cfg.adaptiveDelayPerPing);
		return baseTicks + extra;
	}

	// =====================================================================
	//  Utility
	// =====================================================================

	private static int findHotbarItem(ClientPlayerEntity player, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getStack(i).isIn(tag)) return i;
		}
		return -1;
	}

	private static int findHotbarItemByItem(ClientPlayerEntity player, net.minecraft.item.Item item) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	private static int countItemInHotbar(ClientPlayerEntity player, net.minecraft.item.Item item) {
		int count = 0;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (stack.isOf(item)) count += stack.getCount();
		}
		return count;
	}

	private static boolean hasMending(ItemStack stack) {
		var enchantments = stack.getEnchantments();
		for (var entry : enchantments.getEnchantmentEntries()) {
			if (entry.getKey().getIdAsString().equals("minecraft:mending")) return true;
		}
		return false;
	}

	private static boolean isInventoryNearlyFull(ClientPlayerEntity player) {
		int free = 0;
		for (int i = 0; i < 36; i++) {
			if (player.getInventory().getStack(i).isEmpty()) free++;
		}
		return free <= 2;
	}

	private static void sendPacket(MinecraftClient client, net.minecraft.network.packet.Packet<?> packet) {
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(packet);
		}
	}
}