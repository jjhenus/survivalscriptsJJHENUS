package jjhenus.survival;

import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.stream.Collectors;

public class ShulkerIndex {

    public enum ShulkerRole {
        CLAY_SOURCE,        // Contains clay blocks
        CLAY_BALL_SOURCE,   // Contains clay balls
        DEPOSIT_TARGET,     // Has free space for clay balls
        EMERALD_DEPOSIT,    // Has free space for emeralds
        OTHER
    }

    public static class ShulkerEntry {
        public final BlockPos pos;
        public ShulkerRole role = ShulkerRole.OTHER;
        public int clayBlockCount = 0;
        public int clayBallCount = 0;
        public int emeraldCount = 0;
        public int occupiedSlots = 0;
        public boolean indexed = false;
        public long lastIndexedTick = 0;

        public ShulkerEntry(BlockPos pos) {
            this.pos = pos.toImmutable();
        }

        public int freeSlots() {
            return 27 - occupiedSlots;
        }
    }

    private final Map<BlockPos, ShulkerEntry> shulkers = new LinkedHashMap<>();

    // ── Core Methods ─────────────────────────────────────────────────

    public void clear() {
        shulkers.clear();
    }

    public void scanNearbyShulkers(ClientPlayerEntity player, int radius, boolean acceptAnyColor) {
        if (player == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        BlockPos center = player.getBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    Block block = client.world.getBlockState(pos).getBlock();
                    if (block instanceof ShulkerBoxBlock) {
                        shulkers.computeIfAbsent(pos, ShulkerEntry::new);
                    }
                }
            }
        }
    }

    public void indexShulker(BlockPos pos, List<ItemStack> slots, long worldTick) {
        ShulkerEntry entry = shulkers.computeIfAbsent(pos, ShulkerEntry::new);

        int clayBlocks = 0;
        int clayBalls = 0;
        int emeralds = 0;
        int occupied = 0;

        for (ItemStack stack : slots) {
            if (stack == null || stack.isEmpty()) continue;
            occupied++;
            if (stack.isOf(Items.CLAY)) clayBlocks += stack.getCount();
            if (stack.isOf(Items.CLAY_BALL)) clayBalls += stack.getCount();
            if (stack.isOf(Items.EMERALD)) emeralds += stack.getCount();
        }

        entry.clayBlockCount = clayBlocks;
        entry.clayBallCount = clayBalls;
        entry.emeraldCount = emeralds;
        entry.occupiedSlots = occupied;
        entry.indexed = true;
        entry.lastIndexedTick = worldTick;

        // Assign role by priority
        if (clayBlocks > 0) {
            entry.role = ShulkerRole.CLAY_SOURCE;
        } else if (clayBalls > 0) {
            entry.role = ShulkerRole.CLAY_BALL_SOURCE;
        } else if (emeralds > 0 || entry.freeSlots() > 0) {
            // If it has emeralds already, it's an emerald deposit; otherwise general deposit
            if (emeralds > 0) {
                entry.role = ShulkerRole.EMERALD_DEPOSIT;
            } else {
                entry.role = ShulkerRole.DEPOSIT_TARGET;
            }
        } else {
            entry.role = ShulkerRole.OTHER;
        }
    }

    // ── Query Methods ────────────────────────────────────────────────

    public boolean hasRefillSources() {
        return shulkers.values().stream()
                .anyMatch(e -> e.indexed && e.role == ShulkerRole.CLAY_SOURCE && e.clayBlockCount > 0);
    }

    public boolean hasClayBallSources() {
        return shulkers.values().stream()
                .anyMatch(e -> e.indexed && e.clayBallCount > 0);
    }

    public boolean hasEmeraldDepositTargets() {
        return shulkers.values().stream()
                .anyMatch(e -> e.indexed && e.freeSlots() > 0);
    }

    public boolean hasDepositTargets() {
        return shulkers.values().stream()
                .anyMatch(e -> e.indexed && e.freeSlots() > 0);
    }

    // ── Poll Methods (get next best target) ──────────────────────────

    public ShulkerEntry pollNextRefillSource() {
        return shulkers.values().stream()
                .filter(e -> e.indexed && e.clayBlockCount > 0)
                .max(Comparator.comparingInt(e -> e.clayBlockCount))
                .orElse(null);
    }

    public ShulkerEntry pollNextClayBallSource() {
        return shulkers.values().stream()
                .filter(e -> e.indexed && e.clayBallCount > 0)
                .max(Comparator.comparingInt(e -> e.clayBallCount))
                .orElse(null);
    }

    public ShulkerEntry pollNextDepositTarget() {
        return shulkers.values().stream()
                .filter(e -> e.indexed && e.freeSlots() > 0)
                .max(Comparator.comparingInt(ShulkerEntry::freeSlots))
                .orElse(null);
    }

    public ShulkerEntry pollNextEmeraldDeposit() {
        return shulkers.values().stream()
                .filter(e -> e.indexed && e.freeSlots() > 0)
                .max(Comparator.comparingInt(ShulkerEntry::freeSlots))
                .orElse(null);
    }

    // ── List Methods ─────────────────────────────────────────────────

    public List<ShulkerEntry> getUnindexedShulkers() {
        return shulkers.values().stream()
                .filter(e -> !e.indexed)
                .collect(Collectors.toList());
    }

    public List<ShulkerEntry> getAllShulkers() {
        return new ArrayList<>(shulkers.values());
    }

    // ── Count Methods ────────────────────────────────────────────────

    public int getIndexedCount() {
        return (int) shulkers.values().stream().filter(e -> e.indexed).count();
    }

    public int getTotalCount() {
        return shulkers.size();
    }

    public int getTotalShulkerClayBlocks() {
        return shulkers.values().stream()
                .filter(e -> e.indexed)
                .mapToInt(e -> e.clayBlockCount)
                .sum();
    }

    public int getTotalShulkerClayBalls() {
        return shulkers.values().stream()
                .filter(e -> e.indexed)
                .mapToInt(e -> e.clayBallCount)
                .sum();
    }

    public int getTotalFreeDepositSlots() {
        return shulkers.values().stream()
                .filter(e -> e.indexed && e.freeSlots() > 0)
                .mapToInt(ShulkerEntry::freeSlots)
                .sum();
    }

    // ── Static Player Inventory Helpers ──────────────────────────────

    public static int countPlayerClayBlocks(ClientPlayerEntity player) {
        return countPlayerItem(player, Items.CLAY);
    }

    public static int countPlayerClayBalls(ClientPlayerEntity player) {
        return countPlayerItem(player, Items.CLAY_BALL);
    }

    public static int countPlayerItem(ClientPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        if (player.getOffHandStack().isOf(item)) count += player.getOffHandStack().getCount();
        return count;
    }
}