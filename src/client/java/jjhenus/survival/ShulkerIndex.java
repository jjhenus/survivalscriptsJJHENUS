package jjhenus.survival;

import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public class ShulkerIndex {
    public enum ShulkerRole { CLAY_SOURCE, BALL_SOURCE, DEPOSIT_TARGET, OTHER }

    public static class ShulkerEntry {
        public final BlockPos pos;
        public ShulkerRole role = ShulkerRole.OTHER;
        public int clayCount = 0;
        public boolean indexed = false;

        public ShulkerEntry(BlockPos pos) { this.pos = pos.toImmutable(); }
    }

    private final Map<BlockPos, ShulkerEntry> shulkers = new LinkedHashMap<>();

    public void scan(ClientPlayerEntity player, int radius) {
        // Logic to scan world for ShulkerBoxBlock types
    }

    public void indexShulker(BlockPos pos, List<ItemStack> slots) {
        ShulkerEntry entry = shulkers.computeIfAbsent(pos, ShulkerEntry::new);
        entry.clayCount = (int) slots.stream().filter(s -> s.isOf(Items.CLAY)).count();
        entry.indexed = true;
    }

    public boolean hasRefillSources() {
        return shulkers.values().stream().anyMatch(e -> e.indexed && e.clayCount > 0);
    }
}