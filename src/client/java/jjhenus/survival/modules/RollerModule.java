package jjhenus.survival.modules;

import jjhenus.survival.util.SurvivalUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Hand;

public class RollerModule extends BaseModule {
    private BlockPos workbenchPos = null;
    private boolean isBreaking = false;

    public void setPos(BlockPos pos) { this.workbenchPos = pos; }

    @Override
    public void onTick(MinecraftClient client) {
        if (workbenchPos == null || delayTimer > 0) { delayTimer--; return; }

        if (client.world.isAir(workbenchPos)) {
            // Place
            SurvivalUtils.lookAt(client, workbenchPos.toCenterPos());
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                    new net.minecraft.util.hit.BlockHitResult(workbenchPos.toCenterPos(), Direction.UP, workbenchPos, false));
            delayTimer = 20; // Wait for villager to claim
        } else {
            // Break
            SurvivalUtils.lookAt(client, workbenchPos.toCenterPos());
            client.interactionManager.attackBlock(workbenchPos, Direction.UP);
        }
    }

    @Override
    public void renderHud(DrawContext context, MinecraftClient client, int x, int y) {
        context.drawText(client.textRenderer, "§6§lSurvival Scripts §r§7| §dRoller", x, y, 0xFFFFFF, true);
        context.drawText(client.textRenderer, "§fPos: §7" + (workbenchPos != null ? workbenchPos.toShortString() : "Not Set"), x, y + 11, 0xFFFFFF, true);
    }

    @Override
    public void stop(MinecraftClient client, String reason) {
        SurvivalUtils.stopWalking(client);
    }
}