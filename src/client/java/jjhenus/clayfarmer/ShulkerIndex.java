package jjhenus.clayfarmer;

import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Tracks the contents of all nearby shulker boxes and the player's inventory.
 * Provides a unified view of how many clay blocks / clay balls are available.
 */
public class ShulkerIndex {

    // ═══════════════════════════════════════════════════════════════════
    //  Data Classes
    // ═══════════════════════════════════════════════════════════════════

    public enum ShulkerRole {
        /** Contains clay blocks — used as a refill source. */
        CLAY_SOURCE,
        /** Contains clay balls — used as a refill source for trading. */
        CLAY_BALL_SOURCE,
        /** Empty or has room — used as a deposit target for clay balls. */
        DEPOSIT_TARGET,
        /** Green shulker with room — used to deposit emeralds. */
        EMERALD_DEPOSIT,
        /** Contains other items — ignored. */
        OTHER
    }

    public static class ShulkerEntry {
        public final BlockPos pos;
        public ShulkerRole role;

        /** Number of clay block stacks/items inside (Items.CLAY). */
        public int clayBlockCount = 0;

        /** Number of clay ball items inside (Items.CLAY_BALL). */
        public int clayBallCount = 0;

        /** Number of emerald items inside (Items.EMERALD). */
        public int emeraldCount = 0;

        /** Total occupied slots (0-27). */
        public int occupiedSlots = 0;

        /** The block type of this shulker (for color checks). */
        public Block blockType = null;

        /** Whether this shulker has been opened and indexed. */
        public boolean indexed = false;

        /** Timestamp (world tick) when last indexed. */
        public long lastIndexedTick = 0;

        public ShulkerEntry(BlockPos pos) {
            this.pos = pos.toImmutable();
            this.role = ShulkerRole.OTHER;
        }

        public int freeSlots() {
            return 27 - occupiedSlots;
        }

        @Override
        public String toString() {
            return String.format("Shulker@%s [%s] clay=%d balls=%d emeralds=%d free=%d",
                    pos.toShortString(), role, clayBlockCount, clayBallCount, emeraldCount, freeSlots());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════════

    /** All known shulker entries, keyed by position. */
    private final Map<BlockPos, ShulkerEntry> shulkers = new LinkedHashMap<>();

    /** Ordered list of shulkers that still have clay blocks. */
    private final Deque<BlockPos> refillQueue = new ArrayDeque<>();

    /** Ordered list of shulkers with free space for depositing clay balls. */
    private final Deque<BlockPos> depositQueue = new ArrayDeque<>();

    /** Ordered list of shulkers that have clay balls (for trading refill). */
    private final Deque<BlockPos> clayBallRefillQueue = new ArrayDeque<>();

    /** Ordered list of green shulkers with free space for emeralds. */
    private final Deque<BlockPos> emeraldDepositQueue = new ArrayDeque<>();

    // ═══════════════════════════════════════════════════════════════════
    //  Scanning (block-level — no screen needed)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Scans for shulker boxes around the player and registers them.
     * Does NOT index contents (that requires opening each one).
     */
    public void scanNearbyShulkers(ClientPlayerEntity player, int radius, boolean anyColor) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        // Remove shulkers that no longer exist in the world
        shulkers.entrySet().removeIf(entry ->
                client.world.isAir(entry.getKey()) ||
                        !(client.world.getBlockState(entry.getKey()).getBlock() instanceof ShulkerBoxBlock));

        BlockPos center = player.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
            Block block = client.world.getBlockState(pos).getBlock();
            boolean isShulker = anyColor
                    ? block instanceof ShulkerBoxBlock
                    : (block == Blocks.LIGHT_GRAY_SHULKER_BOX || block == Blocks.GREEN_SHULKER_BOX);

            if (isShulker && !shulkers.containsKey(pos.toImmutable())) {
                ShulkerEntry entry = new ShulkerEntry(pos);
                entry.blockType = block;
                shulkers.put(pos.toImmutable(), entry);
            }
        }
    }

    /**
     * Returns the list of shulkers that have NOT been indexed yet.
     */
    public List<ShulkerEntry> getUnindexedShulkers() {
        List<ShulkerEntry> result = new ArrayList<>();
        for (ShulkerEntry entry : shulkers.values()) {
            if (!entry.indexed) result.add(entry);
        }
        return result;
    }

    /**
     * Returns all known shulker entries.
     */
    public Collection<ShulkerEntry> getAllShulkers() {
        return Collections.unmodifiableCollection(shulkers.values());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Indexing (called after opening a shulker screen)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Index a shulker's contents from its screen handler slots (0-26).
     * Call this while the shulker screen is open.
     */
    public void indexShulker(BlockPos pos, List<ItemStack> slots, long worldTick) {
        ShulkerEntry entry = shulkers.get(pos);
        if (entry == null) {
            entry = new ShulkerEntry(pos);
            shulkers.put(pos.toImmutable(), entry);
        }

        entry.clayBlockCount = 0;
        entry.clayBallCount = 0;
        entry.emeraldCount = 0;
        entry.occupiedSlots = 0;

        for (ItemStack stack : slots) {
            if (stack.isEmpty()) continue;
            entry.occupiedSlots++;
            if (stack.isOf(Items.CLAY)) {
                entry.clayBlockCount += stack.getCount();
            } else if (stack.isOf(Items.CLAY_BALL)) {
                entry.clayBallCount += stack.getCount();
            } else if (stack.isOf(Items.EMERALD)) {
                entry.emeraldCount += stack.getCount();
            }
        }

        // Store block type if not already set
        MinecraftClient client = MinecraftClient.getInstance();
        if (entry.blockType == null && client.world != null) {
            entry.blockType = client.world.getBlockState(pos).getBlock();
        }

        // Classify role based on contents and color
        boolean isGreen = entry.blockType == Blocks.GREEN_SHULKER_BOX;

        if (isGreen && entry.freeSlots() > 0) {
            entry.role = ShulkerRole.EMERALD_DEPOSIT;
        } else if (entry.clayBlockCount > 0) {
            entry.role = ShulkerRole.CLAY_SOURCE;
        } else if (entry.clayBallCount > 0) {
            entry.role = ShulkerRole.CLAY_BALL_SOURCE;
        } else if (entry.freeSlots() > 0) {
            entry.role = ShulkerRole.DEPOSIT_TARGET;
        } else {
            entry.role = ShulkerRole.OTHER;
        }

        entry.indexed = true;
        entry.lastIndexedTick = worldTick;

        rebuildQueues();
    }

    /**
     * Mark a shulker as broken / removed from the world.
     */
    public void removeShulker(BlockPos pos) {
        shulkers.remove(pos);
        rebuildQueues();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Queue Management
    // ═══════════════════════════════════════════════════════════════════

    private void rebuildQueues() {
        refillQueue.clear();
        depositQueue.clear();
        clayBallRefillQueue.clear();
        emeraldDepositQueue.clear();

        for (ShulkerEntry entry : shulkers.values()) {
            if (!entry.indexed) continue;
            if (entry.role == ShulkerRole.CLAY_SOURCE) {
                refillQueue.addLast(entry.pos);
            }
            if (entry.role == ShulkerRole.CLAY_BALL_SOURCE) {
                clayBallRefillQueue.addLast(entry.pos);
            }
            if (entry.role == ShulkerRole.EMERALD_DEPOSIT) {
                emeraldDepositQueue.addLast(entry.pos);
            }
            if (entry.freeSlots() > 0 && entry.role != ShulkerRole.EMERALD_DEPOSIT) {
                depositQueue.addLast(entry.pos);
            }
        }
    }

    /** Get the next shulker to refill clay blocks from, or null. */
    public ShulkerEntry pollNextRefillSource() {
        while (!refillQueue.isEmpty()) {
            BlockPos pos = refillQueue.pollFirst();
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.clayBlockCount > 0) {
                return entry;
            }
        }
        return null;
    }

    /** Peek at the next refill source without removing it. */
    public ShulkerEntry peekNextRefillSource() {
        for (BlockPos pos : refillQueue) {
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.clayBlockCount > 0) {
                return entry;
            }
        }
        return null;
    }

    /** Get the next shulker to deposit clay balls into, or null. */
    public ShulkerEntry pollNextDepositTarget() {
        while (!depositQueue.isEmpty()) {
            BlockPos pos = depositQueue.pollFirst();
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.freeSlots() > 0) {
                return entry;
            }
        }
        return null;
    }

    /** Peek at the next deposit target without removing it. */
    public ShulkerEntry peekNextDepositTarget() {
        for (BlockPos pos : depositQueue) {
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.freeSlots() > 0) {
                return entry;
            }
        }
        return null;
    }

    public boolean hasRefillSources() {
        return peekNextRefillSource() != null;
    }

    public boolean hasDepositTargets() {
        return peekNextDepositTarget() != null;
    }

    // ── Clay Ball Refill Queue (for trading) ────────────────────────────

    /** Get the next shulker with clay balls, or null. */
    public ShulkerEntry pollNextClayBallSource() {
        while (!clayBallRefillQueue.isEmpty()) {
            BlockPos pos = clayBallRefillQueue.pollFirst();
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.clayBallCount > 0) {
                return entry;
            }
        }
        return null;
    }

    public ShulkerEntry peekNextClayBallSource() {
        for (BlockPos pos : clayBallRefillQueue) {
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.clayBallCount > 0) {
                return entry;
            }
        }
        return null;
    }

    public boolean hasClayBallSources() {
        return peekNextClayBallSource() != null;
    }

    // ── Emerald Deposit Queue ───────────────────────────────────────────

    /** Get the next green shulker with free space for emeralds, or null. */
    public ShulkerEntry pollNextEmeraldDeposit() {
        while (!emeraldDepositQueue.isEmpty()) {
            BlockPos pos = emeraldDepositQueue.pollFirst();
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.freeSlots() > 0) {
                return entry;
            }
        }
        return null;
    }

    public ShulkerEntry peekNextEmeraldDeposit() {
        for (BlockPos pos : emeraldDepositQueue) {
            ShulkerEntry entry = shulkers.get(pos);
            if (entry != null && entry.indexed && entry.freeSlots() > 0) {
                return entry;
            }
        }
        return null;
    }

    public boolean hasEmeraldDepositTargets() {
        return peekNextEmeraldDeposit() != null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Inventory Summary
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Count total clay blocks across all indexed shulkers.
     */
    public int getTotalShulkerClayBlocks() {
        int total = 0;
        for (ShulkerEntry entry : shulkers.values()) {
            if (entry.indexed) total += entry.clayBlockCount;
        }
        return total;
    }

    /**
     * Count total clay balls across all indexed shulkers.
     */
    public int getTotalShulkerClayBalls() {
        int total = 0;
        for (ShulkerEntry entry : shulkers.values()) {
            if (entry.indexed) total += entry.clayBallCount;
        }
        return total;
    }

    /**
     * Count clay blocks in the player's entire inventory (hotbar + main).
     */
    public static int countPlayerClayBlocks(ClientPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.CLAY)) count += stack.getCount();
        }
        // Also check offhand
        if (player.getOffHandStack().isOf(Items.CLAY)) {
            count += player.getOffHandStack().getCount();
        }
        return count;
    }

    /**
     * Count clay balls in the player's entire inventory (hotbar + main).
     */
    public static int countPlayerClayBalls(ClientPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.CLAY_BALL)) count += stack.getCount();
        }
        if (player.getOffHandStack().isOf(Items.CLAY_BALL)) {
            count += player.getOffHandStack().getCount();
        }
        return count;
    }

    /**
     * Count total emeralds across all indexed shulkers.
     */
    public int getTotalShulkerEmeralds() {
        int total = 0;
        for (ShulkerEntry entry : shulkers.values()) {
            if (entry.indexed) total += entry.emeraldCount;
        }
        return total;
    }

    /**
     * Get total free deposit slots across all indexed shulkers.
     */
    public int getTotalFreeDepositSlots() {
        int total = 0;
        for (ShulkerEntry entry : shulkers.values()) {
            if (entry.indexed && entry.freeSlots() > 0) {
                total += entry.freeSlots();
            }
        }
        return total;
    }

    /**
     * Number of indexed shulkers.
     */
    public int getIndexedCount() {
        int count = 0;
        for (ShulkerEntry entry : shulkers.values()) {
            if (entry.indexed) count++;
        }
        return count;
    }

    /**
     * Total shulkers found (indexed or not).
     */
    public int getTotalCount() {
        return shulkers.size();
    }

    /**
     * Full reset.
     */
    public void clear() {
        shulkers.clear();
        refillQueue.clear();
        depositQueue.clear();
        clayBallRefillQueue.clear();
        emeraldDepositQueue.clear();
    }
}