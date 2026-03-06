package jjhenus.survival.util;

import jjhenus.survival.SurvivalConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

public class SurvivalUtils {

    /**
     * Smoothly look at a target position.
     * Guards against NaN when player is exactly at target.
     */
    public static void lookAt(MinecraftClient client, Vec3d target) {
        if (client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        Vec3d diff = target.subtract(eye);

        // Guard: if player eye is exactly at target, skip to avoid NaN
        if (diff.lengthSquared() < 0.0001) return;

        diff = diff.normalize();
        client.player.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        client.player.setPitch((float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z))));
    }

    /**
     * Release all movement keys.
     */
    public static void stopWalking(MinecraftClient client) {
        if (client.player != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }
    }

    /**
     * Count how many of an item the player has in their inventory + offhand.
     */
    public static int countItem(MinecraftClient client, Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        if (client.player.getOffHandStack().isOf(item)) count += client.player.getOffHandStack().getCount();
        return count;
    }

    /**
     * Calculate a delay adapted to the player's ping.
     * Higher ping = more delay to prevent desync.
     */
    public static int getAdaptedDelay(int baseTicks, MinecraftClient client) {
        double factor = SurvivalConfig.get().adaptiveDelayPerPing;
        if (factor <= 0 || client.getNetworkHandler() == null || client.player == null) return baseTicks;
        var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        if (entry == null) return baseTicks;
        int ping = entry.getLatency();
        int extra = (int) Math.ceil((ping / 50.0) * factor);
        return baseTicks + extra;
    }
}