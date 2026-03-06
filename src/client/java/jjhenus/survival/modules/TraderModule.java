package jjhenus.survival.modules;

import jjhenus.survival.SurvivalScriptsClient;
import jjhenus.survival.util.SurvivalUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Hand;
import net.minecraft.village.VillagerData;
import net.minecraft.util.math.Box;
import java.util.Comparator;
import java.util.List;

public class TraderModule extends BaseModule {
    private VillagerEntity currentTarget = null;

    @Override
    public void onTick(MinecraftClient client) {
        if (delayTimer > 0) { delayTimer--; return; }

        if (currentTarget == null || !currentTarget.isAlive()) {
            currentTarget = findMasons(client);
            return;
        }

        double dist = client.player.getPos().distanceTo(currentTarget.getPos());
        if (dist > 3.0) {
            SurvivalUtils.lookAt(client, currentTarget.getEyePos());
            client.options.forwardKey.setPressed(true);
        } else {
            SurvivalUtils.stopWalking(client);
            client.interactionManager.interactEntity(client.player, currentTarget, Hand.MAIN_HAND);
            delayTimer = 20; // Wait for Merchant Screen
        }
    }

    private VillagerEntity findMasons(MinecraftClient client) {
        Box box = new Box(client.player.getPos()).expand(64);
        List<VillagerEntity> masons = client.world.getEntitiesByClass(VillagerEntity.class, box, v ->
                v.getVillagerData().profession().matchesId(net.minecraft.util.Identifier.ofVanilla("mason")));
        return masons.stream().min(Comparator.comparingDouble(v -> v.squaredDistanceTo(client.player))).orElse(null);
    }

    @Override
    public void renderHud(DrawContext context, MinecraftClient client, int x, int y) {
        context.drawText(client.textRenderer, "§6§lSurvival Scripts §r§7| §2Trader", x, y, 0xFFFFFF, true);
        String name = currentTarget != null ? "Mason" : "Scanning...";
        context.drawText(client.textRenderer, "§fTarget: §7" + name, x, y + 11, 0xFFFFFF, true);
    }

    @Override
    public void stop(MinecraftClient client, String reason) {
        SurvivalUtils.stopWalking(client);
    }
}