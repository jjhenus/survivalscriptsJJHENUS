package jjhenus.survival.util;

import jjhenus.survival.SurvivalConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

public class SurvivalUtils {
    public static void lookAt(MinecraftClient client, Vec3d target) {
        if (client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        Vec3d diff = target.subtract(eye).normalize();
        client.player.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        client.player.setPitch((float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z))));
    }

    public static void stopWalking(MinecraftClient client) {
        if (client.player != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }
    }

    public static int countItem(MinecraftClient client, Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    public static int getAdaptedDelay(int baseTicks, MinecraftClient client) {
        double factor = SurvivalConfig.get().adaptiveDelayPerPing;
        if (client.getNetworkHandler() == null) return baseTicks;
        var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        int ping = (entry != null) ? entry.getLatency() : 0;
        return baseTicks + (int) Math.ceil((ping / 50.0) * factor);
    }
}