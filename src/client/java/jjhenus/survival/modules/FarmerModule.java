package jjhenus.survival.modules;

import jjhenus.survival.SurvivalConfig;
import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.util.SurvivalUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class FarmerModule extends BaseModule {
	private enum State { PLACING, BREAKING, REPAIRING, REFILLING, DEPOSITING }
	private State currentState = State.PLACING;
	private BlockPos targetPos = null;

	@Override
	public void onTick(MinecraftClient client) {
		if (delayTimer > 0) { delayTimer--; return; }

		// Watchdog check
		watchdogTicks++;
		if (watchdogTicks > 400) { stop(client, "Watchdog Timeout"); return; }

		switch (currentState) {
			case PLACING -> handlePlacing(client);
			case BREAKING -> handleBreaking(client);
			case REPAIRING -> handleRepairing(client);
		}
	}

	private void handlePlacing(MinecraftClient client) {
		if (!client.player.getOffHandStack().isOf(Items.CLAY)) {
			currentState = State.REFILLING;
			return;
		}

		if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
			BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
			targetPos = hit.getBlockPos().offset(hit.getSide());
			client.interactionManager.interactBlock(client.player, Hand.OFF_HAND, hit);
			delayTimer = SurvivalUtils.getAdaptedDelay(SurvivalConfig.get().placeDelay, client);
			watchdogTicks = 0;
			currentState = State.BREAKING;
		}
	}

	private void handleBreaking(MinecraftClient client) {
		if (targetPos == null || client.world.isAir(targetPos)) {
			currentState = State.PLACING;
			return;
		}
		client.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
		client.player.swingHand(Hand.MAIN_HAND);
		if (client.world.isAir(targetPos)) {
			watchdogTicks = 0;
			currentState = State.PLACING;
		}
	}

	private void handleRepairing(MinecraftClient client) {
		if (client.player == null) return; // Guard clause to prevent NullPointerException

		// Use getPos() to get the player's current Vec3d position
		SurvivalUtils.lookAt(client, client.player.getPos().add(0, -1, 0));

		client.interactionManager.interactItem(client.player, Hand.OFF_HAND);

		// Ensure SurvivalConfig is properly initialized
		delayTimer = SurvivalConfig.get().repairThrowDelay;
	}

	@Override
	public void renderHud(DrawContext context, MinecraftClient client, int x, int y) {
		context.drawText(client.textRenderer, "§6§lSurvival Scripts §r§7| §aFarmer", x, y, 0xFFFFFF, true);
		context.drawText(client.textRenderer, "§fState: §7" + currentState.name(), x, y + 11, 0xFFFFFF, true);
	}

	@Override
	public void stop(MinecraftClient client, String reason) {
		SurvivalUtils.stopWalking(client);
	}
}