package com.agen.autoshopbuyer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * State machine for the auto-buy task.
 * States: IDLE -> CHECK_TERMINAL -> SWAP_TERMINAL -> SEARCH_ITEM -> CALCULATE -> BUYING -> WAIT_ITEMS -> DEPOSITING -> (next batch or COMPLETE)
 */
public class ShopBuyerTask {
    public enum State {
        IDLE, CHECK_TERMINAL, SWAP_TERMINAL, SEARCH_ITEM, CALCULATE,
        BUYING, WAIT_ITEMS, DEPOSITING,
        FALLBACK_OPENING_GRID, FALLBACK_WAIT_GUI, FALLBACK_INSERTING, FALLBACK_CLOSING,
        RECOVERY_DEPOSIT,
        COMPLETE, FAILED, ABORTED
    }

    private State state = State.IDLE;
    private final String targetItemName;
    private final int targetTotalCount; // 0 = unlimited

    // Shop entry info
    private UUID shopTabUUID;
    private UUID shopEntryUUID;
    private ItemStack targetItemStack;
    private int entryCount; // items per transaction
    private long entryPrice;

    // Task progress
    private int currentBatchSize;
    private int tickCounter;
    private int totalBought;
    private int totalDeposited;
    private Vec3 startPosition;
    private boolean buyPacketSent;
    private int swapTimer;
    private final int intervalTicks; // configurable wait ticks between buy and deposit

    // Track items in inventory BEFORE buying, so we only deposit newly bought items
    private final Map<String, Integer> inventoryBeforeBuy = new HashMap<>();

    // Fallback deposit state (when server doesn't have the mod)
    private boolean useFallbackDeposit = false;
    private int fallbackTerminalSlot = -1;
    private int fallbackOriginalSelected = -1;
    private int fallbackCurrentSlot = 0;
    private int fallbackTimer = 0;

    // Cached reflection objects for buy packet (avoid repeated Class.forName lookups)
    private static Class<?> cachedBuyMsgClass;
    private static Constructor<?> cachedBuyMsgConstructor;
    private static Method cachedSendToServerMethod;

    // Batch counter for progress display
    private int batchNumber = 0;

    // Recovery deposit state (deposit leftover target items when no empty slots)
    private boolean recoveryMode = false;
    private final java.util.Set<Integer> recoveryDepositedSlots = new java.util.HashSet<>();
    private boolean recoveryDepositedAny = false;
    private int recoveryRetryCount = 0;
    private static final int MAX_RECOVERY_RETRIES = 3;

    // Purchase freeze state: when breakpoint protection triggers, freeze purchasing until empty slots detected
    private boolean purchaseFrozen = false;



    public ShopBuyerTask(String targetItemName) {
        this(targetItemName, 0, 3);
    }

    public ShopBuyerTask(String targetItemName, int targetTotalCount) {
        this(targetItemName, targetTotalCount, 3);
    }

    public ShopBuyerTask(String targetItemName, int targetTotalCount, int intervalTicks) {
        this.targetItemName = targetItemName;
        this.targetTotalCount = targetTotalCount;
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            state = State.FAILED;
            return;
        }

        switch (state) {
            case IDLE:
                state = State.CHECK_TERMINAL;
                break;

            case CHECK_TERMINAL:
                if (RSNetworkHelper.hasValidWirelessTerminal(player)) {
                    // Already holding valid terminal
                    startPosition = player.position();
                    AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Terminal check passed (already holding)");
                    state = State.SEARCH_ITEM;
                } else {
                    // Try to find and swap terminal from inventory
                    int terminalSlot = findWirelessTerminalInInventory(player);
                    if (terminalSlot >= 0) {
                        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Found terminal in slot {}, swapping to main hand", terminalSlot);
                        swapTerminalToMainHand(player, terminalSlot);
                        swapTimer = 0;
                        state = State.SWAP_TERMINAL;
                    } else {
                        fail("\u80cc\u5305\u4e2d\u627e\u4e0d\u5230\u6709\u6548\u7684\u65e0\u7ebf\u5408\u6210\u7ec8\u7aef\uff01");
                    }
                }
                break;

            case SWAP_TERMINAL:
                swapTimer++;
                if (swapTimer >= 2) {
                    // Verify the swap worked
                    if (RSNetworkHelper.hasValidWirelessTerminal(player)) {
                        startPosition = player.position();
                        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Terminal swap successful");
                        state = State.SEARCH_ITEM;
                    } else {
                        fail("\u5207\u6362\u65e0\u7ebf\u7ec8\u7aef\u5230\u4e3b\u624b\u5931\u8d25\uff01");
                    }
                }
                break;

            case SEARCH_ITEM:
                if (!searchItemInShop()) {
                    fail("\u5728\u5546\u5e97\u4e2d\u627e\u4e0d\u5230\u7269\u54c1 '" + targetItemName + "'\uff01");
                    return;
                }
                AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Found item: {} (entryCount={}, price={})",
                        targetItemStack.getItem(), entryCount, entryPrice);
                state = State.CALCULATE;
                break;

            case CALCULATE:
                // If purchase is frozen (breakpoint protection active), do not proceed
                if (purchaseFrozen) {
                    // Wait until unfrozen (unfreeze happens in handleFallbackClosing when empty slots detected)
                    break;
                }
                // Check if target count reached
                if (targetTotalCount > 0 && totalBought >= targetTotalCount) {
                    state = State.COMPLETE;
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u76ee\u6807\u5df2\u8fbe\u6210\uff01" + buildProgressInfo() + " \u5df2\u5165\u5e93: " + totalDeposited));
                    return;
                }
                calculateBatch();
                // If calculateBatch triggered recovery deposit (state changed), don't override it
                if (state != State.CALCULATE) {
                    break;
                }
                if (currentBatchSize <= 0) {
                    state = State.COMPLETE;
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6ca1\u6709\u7a7a\u4f59\u683c\u5b50\u4e86\u3002" + buildProgressInfo() + ", \u5df2\u5165\u5e93: " + totalDeposited));
                    return;
                }
                // Don't over-buy: if we'd exceed target, reduce batch
                if (targetTotalCount > 0) {
                    int remaining = targetTotalCount - totalBought;
                    int maxTransactions = (remaining + entryCount - 1) / entryCount; // ceil division
                    currentBatchSize = Math.min(currentBatchSize, maxTransactions);
                }
                batchNumber++;
                String targetStr = targetTotalCount > 0 ? "(\u76ee\u6807: " + targetTotalCount + ")" : "";
                String progressStr = buildProgressInfo();
                AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Batch #{}: buying {} times (entryCount={}){}",
                        batchNumber, currentBatchSize, entryCount, targetStr);
                tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u7b2c" + batchNumber + "\u8f6e: \u8d2d\u4e70 " + currentBatchSize + "x " + targetItemName
                        + " (" + (currentBatchSize * entryCount) + " \u4ef6)" + targetStr + progressStr));
                buyPacketSent = false;
                state = State.BUYING;
                break;

            case BUYING:
                if (!buyPacketSent) {
                    // Check abort conditions before buying
                    if (checkAbort(player)) return;
                    // Snapshot inventory BEFORE buying
                    snapshotInventoryBeforeBuy(player);
                    sendBuyPacket(player);
                    buyPacketSent = true;
                    tickCounter = 0;
                }
                tickCounter++;
                // Wait 1 tick for server to process
                if (tickCounter >= 1) {
                    tickCounter = 0;
                    state = State.WAIT_ITEMS;
                }
                break;

            case WAIT_ITEMS:
                if (checkAbort(player)) return;
                tickCounter++;
                if (tickCounter >= intervalTicks) {
                    tickCounter = 0;
                    state = State.DEPOSITING;
                }
                break;

            case DEPOSITING:
                if (checkAbort(player)) return;
                depositItems(player);
                // depositItems() always starts fallback deposit (RS GUI method)
                // If there were items to deposit, state changed to FALLBACK_OPENING_GRID
                // If no items, stay and go back to CALCULATE
                if (state == State.DEPOSITING) {
                    // No items to deposit, go back to calculate next batch
                    tickCounter = 0;
                    state = State.CALCULATE;
                }
                break;

            case FALLBACK_OPENING_GRID:
                handleFallbackOpeningGrid(player);
                break;

            case FALLBACK_WAIT_GUI:
                handleFallbackWaitGui(player);
                break;

            case FALLBACK_INSERTING:
                handleFallbackInserting(player);
                break;

            case FALLBACK_CLOSING:
                handleFallbackClosing(player);
                break;

            case RECOVERY_DEPOSIT:
                handleRecoveryDeposit(player);
                break;

            case COMPLETE:
            case FAILED:
            case ABORTED:
                // Terminal states - do nothing
                break;
        }
    }

    /**
     * Snapshot the current inventory item counts before buying.
     * This is used later to only deposit newly bought items (not pre-existing ones).
     */
    private void snapshotInventoryBeforeBuy(LocalPlayer player) {
        inventoryBeforeBuy.clear();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty()) continue;
            String itemKey = ForgeRegistries.ITEMS.getKey(slot.getItem()).toString();
            inventoryBeforeBuy.merge(itemKey, slot.getCount(), Integer::sum);
        }
        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Inventory snapshot taken: {} item types", inventoryBeforeBuy.size());
    }

    /**
     * Check if an item type + count represents newly bought items.
     * Compares current inventory against the pre-buy snapshot.
     * @return the count of newly acquired items of this type (0 or negative = none)
     */
    private int getNewlyBoughtCount(LocalPlayer player, String itemKey) {
        int currentCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty()) continue;
            String slotKey = ForgeRegistries.ITEMS.getKey(slot.getItem()).toString();
            if (slotKey.equals(itemKey)) {
                currentCount += slot.getCount();
            }
        }
        int beforeCount = inventoryBeforeBuy.getOrDefault(itemKey, 0);
        return currentCount - beforeCount;
    }

    /**
     * Check abort conditions. Only checks terminal during active buy/deposit phases.
     */
    private boolean checkAbort(LocalPlayer player) {
        // Check if player moved too far
        if (startPosition != null) {
            double dist = player.position().distanceTo(startPosition);
            if (dist > Config.moveTolerance) {
                abort("\u73a9\u5bb6\u79fb\u52a8\u8fc7\u8fdc\uff01\u81ea\u52a8\u8d2d\u4e70\u5df2\u4e2d\u65ad\u3002");
                return true;
            }
        }
        // Check if player opened a screen (ignore ShopBuyerScreen itself)
        if (Minecraft.getInstance().screen != null && !(Minecraft.getInstance().screen instanceof ShopBuyerScreen)) {
            abort("\u73a9\u5bb6\u6253\u5f00\u4e86\u5176\u4ed6\u754c\u9762\uff01\u81ea\u52a8\u8d2d\u4e70\u5df2\u4e2d\u65ad\u3002");
            return true;
        }
        // Only check terminal during buy/wait/deposit phases (not during initial setup)
        if (state == State.BUYING || state == State.WAIT_ITEMS || state == State.DEPOSITING) {
            if (!RSNetworkHelper.hasValidWirelessTerminal(player)) {
                abort("\u73a9\u5bb6\u4e0d\u518d\u6301\u6709\u65e0\u7ebf\u7ec8\u7aef\uff01\u81ea\u52a8\u8d2d\u4e70\u5df2\u4e2d\u65ad\u3002");
                return true;
            }
        }
        return false;
    }

    /**
     * Find a valid wireless terminal in the player's main inventory (slots 0-35, excluding armor/offhand).
     * @return slot index (0-35), or -1 if not found
     */
    private int findWirelessTerminalInInventory(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (RSNetworkHelper.isWirelessTerminal(stack) && RSNetworkHelper.isNetworkItemValid(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Swap the terminal to main hand by changing the selected hotbar slot.
     * If the terminal is in the hotbar (0-8), just select that slot.
     * If the terminal is in main inventory (9-35), swap it with the currently selected hotbar slot first.
     */
    private void swapTerminalToMainHand(LocalPlayer player, int slot) {
        int currentSelected = player.getInventory().selected;
        if (slot < 9) {
            // Terminal is in hotbar - just select that slot
            player.getInventory().selected = slot;
        } else {
            // Terminal is in main inventory - swap with current selected hotbar slot
            ItemStack hotbarItem = player.getInventory().getItem(currentSelected).copy();
            ItemStack terminal = player.getInventory().getItem(slot).copy();
            player.getInventory().setItem(currentSelected, terminal);
            player.getInventory().setItem(slot, hotbarItem);
            player.getInventory().setChanged();
        }
        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Swapped terminal to main hand: slot {} -> selected {}", slot, player.getInventory().selected);
    }

    private boolean searchItemInShop() {
        try {
            // Access ShopBase.CLIENT
            Class<?> shopBaseClass = Class.forName("net.sixik.sdmshoprework.common.shop.ShopBase");
            Object clientShop = shopBaseClass.getField("CLIENT").get(null);
            if (clientShop == null) {
                AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] ShopBase.CLIENT is null");
                return false;
            }

            // Get shop tabs
            java.util.List<?> tabs = (java.util.List<?>) shopBaseClass.getMethod("getShopTabs").invoke(clientShop);
            if (tabs == null || tabs.isEmpty()) {
                AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] No shop tabs found");
                return false;
            }

            String searchLower = targetItemName.toLowerCase();

            for (Object tab : tabs) {
                // Get tab UUID
                java.lang.reflect.Field tabUUIDField = tab.getClass().getSuperclass().getField("shopTabUUID");
                UUID tabUUID = (UUID) tabUUIDField.get(tab);

                // Get entries
                java.util.List<?> entries = (java.util.List<?>) tab.getClass().getMethod("getTabEntry").invoke(tab);
                if (entries == null) continue;

                for (Object entry : entries) {
                    // Get entry type
                    Object entryType = entry.getClass().getMethod("getEntryType").invoke(entry);
                    if (entryType == null) continue;

                    // Check if it's a ShopItemEntryType
                    String typeName = entryType.getClass().getName();
                    if (!typeName.endsWith("ShopItemEntryType")) continue;

                    // Get itemStack from ShopItemEntryType
                    java.lang.reflect.Field itemStackField = entryType.getClass().getField("itemStack");
                    ItemStack itemStack = (ItemStack) itemStackField.get(entryType);
                    if (itemStack == null || itemStack.isEmpty()) continue;

                    // Match by item name
                    String itemName = itemStack.getHoverName().getString().toLowerCase();
                    String registryName = "";
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                    if (key != null) {
                        registryName = key.toString().toLowerCase();
                    }

                    if (itemName.contains(searchLower) || registryName.contains(searchLower)) {
                        // Found the item!
                        this.shopTabUUID = tabUUID;

                        // Get entry UUID
                        java.lang.reflect.Field entryUUIDField = entry.getClass().getField("entryUUID");
                        this.shopEntryUUID = (UUID) entryUUIDField.get(entry);

                        // Get entry count and price
                        java.lang.reflect.Field entryCountField = entry.getClass().getField("entryCount");
                        this.entryCount = (int) entryCountField.get(entry);

                        java.lang.reflect.Field entryPriceField = entry.getClass().getField("entryPrice");
                        this.entryPrice = (long) entryPriceField.get(entry);

                        this.targetItemStack = itemStack.copy();
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] Error searching shop", e);
        }
        return false;
    }

    /**
     * Calculate how many buy transactions to perform based on available inventory capacity.
     * Formula: buyTimes = (emptySlots * maxStackSize) / entryCount
     * Only counts hotbar (0-8) and backpack (9-35) slots, excluding armor/offhand.
     */
    private void calculateBatch() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            currentBatchSize = 0;
            return;
        }

        // Determine the max stack size of the target item
        int maxStackSize = 64; // default
        if (targetItemStack != null && !targetItemStack.isEmpty()) {
            maxStackSize = targetItemStack.getMaxStackSize();
        }
        maxStackSize = Math.max(1, maxStackSize);

        // Count empty slots in hotbar (0-8) and backpack (9-35) only
        // Slots 36-40 are armor/offhand and must NOT be counted
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack slot = mc.player.getInventory().getItem(i);
            if (slot.isEmpty()) {
                emptySlots++;
            } else if (RSNetworkHelper.isWirelessTerminal(slot)) {
                // Terminal occupies a slot, not available for items
            }
        }

        // Total capacity in items: emptySlots * maxStackSize
        int totalItemCapacity = emptySlots * maxStackSize;

        if (totalItemCapacity <= 0) {
            // No empty slots - check if there are leftover target items to deposit first
            if (hasTargetItemsInInventory(mc.player)) {
                AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] No empty slots but found leftover target items, entering recovery deposit");
                tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6ca1\u6709\u7a7a\u69fd\uff0c\u68c0\u6d4b\u5230\u80cc\u5305\u4e2d\u6709\u76ee\u6807\u7269\u54c1\uff0c\u5148\u5165\u5e93..."));
                // Freeze purchasing immediately until empty slots are detected again
                purchaseFrozen = true;
                recoveryMode = true;
                recoveryDepositedSlots.clear();
                recoveryDepositedAny = false;
                int terminalSlot = findWirelessTerminalInInventory(mc.player);
                if (terminalSlot < 0) terminalSlot = mc.player.getInventory().selected;
                startFallbackDeposit(mc.player, terminalSlot);
                return;
            }
            currentBatchSize = 0;
            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] calculateBatch: no capacity available (emptySlots={}, maxStackSize={})",
                    emptySlots, maxStackSize);
            return;
        }

        // Each buy gives entryCount items.
        // buyTimes = (emptySlots * maxStackSize) / entryCount
        int effectiveEntryCount = Math.max(1, entryCount);
        currentBatchSize = totalItemCapacity / effectiveEntryCount;

        // Ensure at least 1 transaction if there is any capacity
        if (currentBatchSize <= 0 && totalItemCapacity > 0) {
            currentBatchSize = 1;
        }

        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] calculateBatch: emptySlots={}, maxStackSize={}, totalCapacity={}, entryCount={}, batchSize={}",
                emptySlots, maxStackSize, totalItemCapacity, entryCount, currentBatchSize);
    }

    /**
     * Check if the player's inventory contains items matching the target purchase item.
     */
    private boolean hasTargetItemsInInventory(LocalPlayer player) {
        if (targetItemStack == null || targetItemStack.isEmpty()) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || RSNetworkHelper.isWirelessTerminal(slot)) continue;
            if (ItemStack.isSameItemSameTags(slot, targetItemStack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the first inventory slot containing the target item that hasn't been deposited yet in recovery mode.
     * @return slot index, or -1 if none found
     */
    private int findNextTargetItemSlot(LocalPlayer player) {
        if (targetItemStack == null || targetItemStack.isEmpty()) return -1;
        for (int i = 0; i < 36; i++) {
            if (recoveryDepositedSlots.contains(i)) continue;
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || RSNetworkHelper.isWirelessTerminal(slot)) continue;
            if (ItemStack.isSameItemSameTags(slot, targetItemStack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * RECOVERY_DEPOSIT: Deposit one stack of target item per tick into the RS terminal.
     * This handles the case where leftover items from interrupted purchases fill up the inventory.
     */
    private void handleRecoveryDeposit(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();

        // Check GUI is still open
        if (!isGridGuiOpen(player)) {
            AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Recovery: grid GUI closed during deposit");
            tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] RS\u7ec8\u7aef\u754c\u9762\u610f\u5916\u5173\u95ed\uff0c\u6062\u590d\u5165\u5e93\u4e2d\u65ad"));
            state = State.FALLBACK_CLOSING;
            fallbackTimer = 0;
            return;
        }

        // Find next target item slot to deposit
        int slot = findNextTargetItemSlot(player);
        if (slot < 0) {
            // All target items deposited
            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Recovery: all target items deposited");
            state = State.FALLBACK_CLOSING;
            fallbackTimer = 0;
            return;
        }

        ItemStack slotItem = player.getInventory().getItem(slot);
        int countBefore = slotItem.getCount();

        // Find the container slot index in the open RS Grid
        int containerSlot = getContainerSlotIndex(player, slot);
        if (containerSlot < 0) {
            AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Recovery: could not find container slot for inventory slot {}", slot);
            recoveryDepositedSlots.add(slot); // Skip this slot
            return;
        }

        // QUICK_MOVE to deposit this stack into RS network
        mc.gameMode.handleInventoryMouseClick(
                player.containerMenu.containerId,
                containerSlot,
                0,
                ClickType.QUICK_MOVE,
                player
        );

        totalDeposited += countBefore;
        recoveryDepositedAny = true;
        recoveryDepositedSlots.add(slot);

        // Clear client-side inventory slot to keep state in sync
        player.getInventory().setItem(slot, ItemStack.EMPTY);
        player.getInventory().setChanged();

        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Recovery: deposited {} x{} from slot {}", targetItemName, countBefore, slot);
        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6062\u590d\u5165\u5e93: " + targetItemName + " x" + countBefore));
    }

    private void sendBuyPacket(LocalPlayer player) {
        try {
            // Use cached reflection for repeated calls (avoids Class.forName overhead)
            if (cachedBuyMsgClass == null) {
                cachedBuyMsgClass = Class.forName("net.sixik.sdmshoprework.network.client.SendBuyShopEntryC2S");
                cachedBuyMsgConstructor = cachedBuyMsgClass.getConstructor(UUID.class, UUID.class, int.class);
                cachedSendToServerMethod = cachedBuyMsgClass.getMethod("sendToServer");
            }
            Object msg = cachedBuyMsgConstructor.newInstance(shopTabUUID, shopEntryUUID, currentBatchSize);
            cachedSendToServerMethod.invoke(msg);

            totalBought += currentBatchSize * entryCount;
            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Sent buy packet: tab={}, entry={}, batchCount={}, totalItems={}",
                    shopTabUUID, shopEntryUUID, currentBatchSize, currentBatchSize * entryCount);
        } catch (Exception e) {
            AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] Failed to send buy packet", e);
            fail("\u53d1\u9001\u8d2d\u4e70\u5305\u5931\u8d25: " + e.getMessage());
        }
    }

    /**
     * Deposit items into the RS network via the wireless terminal GUI (client-side only).
     * Opens the RS terminal GUI and inserts items one by one using simulated clicks.
     * @return number of items to deposit (actual deposit happens in fallback state machine)
     */
    private int depositItems(LocalPlayer player) {
        // Find terminal slot on client side
        int terminalSlot = player.getInventory().selected; // After swap, terminal should be in selected slot
        ItemStack mainHand = player.getMainHandItem();
        if (!RSNetworkHelper.isWirelessTerminal(mainHand)) {
            // Fallback: search for terminal in inventory
            terminalSlot = findWirelessTerminalInInventory(player);
            if (terminalSlot < 0) {
                AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] No terminal found during deposit!");
                return 0;
            }
        }

        // Count items to deposit (only newly bought items, not pre-existing ones)
        int itemCountBefore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || RSNetworkHelper.isWirelessTerminal(slot)) continue;
            String itemKey = ForgeRegistries.ITEMS.getKey(slot.getItem()).toString();
            int newlyBought = getNewlyBoughtCount(player, itemKey);
            if (newlyBought > 0) {
                itemCountBefore += Math.min(slot.getCount(), newlyBought);
            }
        }

        if (itemCountBefore == 0) {
            // Nothing to deposit, skip straight to next batch
            return 0;
        }

        // Always use RS GUI fallback deposit (client-side only, no server mod required)
        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Using RS GUI fallback deposit (client-side only)");
        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u901a\u8fc7RS\u7ec8\u7aefGUI\u5165\u5e93\u4e2d..."));
        startFallbackDeposit(player, terminalSlot);
        return 0; // Items will be deposited by the fallback state machine
    }

    // ==================== Fallback RS GUI Deposit ====================

    /**
     * Initialize the fallback deposit process (RS GUI based).
     * Used when the server doesn't have the AutoShopBuyer mod.
     */
    private void startFallbackDeposit(LocalPlayer player, int terminalSlot) {
        useFallbackDeposit = true;
        fallbackTerminalSlot = terminalSlot;
        fallbackOriginalSelected = player.getInventory().selected;
        fallbackCurrentSlot = 0;
        fallbackTimer = 0;

        // Find the grid in inventory (may be different from terminalSlot if terminal is in main hand)
        int gridSlot = findWirelessTerminalInInventory(player);
        if (gridSlot < 0) gridSlot = terminalSlot;
        fallbackTerminalSlot = gridSlot;

        state = State.FALLBACK_OPENING_GRID;
    }

    /**
     * FALLBACK_OPENING_GRID: Switch to the grid slot and send right-click to open GUI.
     * Includes a small delay to ensure previous GUI is fully closed before opening a new one.
     */
    private void handleFallbackOpeningGrid(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();

        // Wait a few ticks before opening to ensure previous GUI is fully closed
        fallbackTimer++;
        if (fallbackTimer < 3) {
            return;
        }

        // Force close any lingering screen
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }

        // If grid is in hotbar, just select it
        if (fallbackTerminalSlot < 9) {
            player.getInventory().selected = fallbackTerminalSlot;
        } else {
            // Grid is in main inventory - swap to hotbar first
            swapToHotbar(player, fallbackTerminalSlot);
            fallbackTerminalSlot = player.getInventory().selected;
        }

        // Send right-click to open the grid GUI
        player.getInventory().selected = fallbackTerminalSlot;
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback: opening RS grid GUI (slot {}), recoveryMode={}", fallbackTerminalSlot, recoveryMode);

        state = State.FALLBACK_WAIT_GUI;
        fallbackTimer = 0;
    }

    /**
     * FALLBACK_WAIT_GUI: Wait for the RS grid GUI to open and stabilize.
     */
    private void handleFallbackWaitGui(LocalPlayer player) {
        fallbackTimer++;

        // Timeout protection (max 40 ticks = 2 seconds)
        if (fallbackTimer > 40) {
            AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Fallback: timeout waiting for grid GUI, recoveryMode={}", recoveryMode);
            restoreFallbackState(player);
            useFallbackDeposit = false;

            if (recoveryMode) {
                recoveryMode = false;
                if (recoveryRetryCount < MAX_RECOVERY_RETRIES) {
                    // Retry: attempt to open GUI again (purchase stays frozen)
                    recoveryRetryCount++;
                    AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Recovery GUI timeout, retry {}/{}", recoveryRetryCount, MAX_RECOVERY_RETRIES);
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6253\u5f00RS\u7ec8\u7aef\u8d85\u65f6\uff0c\u5c1d\u8bd5\u7b2c" + recoveryRetryCount + "/" + MAX_RECOVERY_RETRIES + "\u6b21\u91cd\u8bd5..."));
                    if (hasTargetItemsInInventory(player)) {
                        recoveryMode = true;
                        recoveryDepositedSlots.clear();
                        recoveryDepositedAny = false;
                        int terminalSlot = findWirelessTerminalInInventory(player);
                        if (terminalSlot < 0) terminalSlot = player.getInventory().selected;
                        startFallbackDeposit(player, terminalSlot);
                    } else {
                        // No target items, unfreeze and recalculate
                        purchaseFrozen = false;
                        recoveryRetryCount = 0;
                        state = State.CALCULATE;
                    }
                } else {
                    AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Recovery GUI timeout, max retries reached");
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6253\u5f00RS\u7ec8\u7aef\u8d85\u65f6\uff0c\u5df2\u8fbe\u6700\u5927\u91cd\u8bd5\u6b21\u6570\uff0c\u505c\u6b62"));
                    purchaseFrozen = false;
                    recoveryRetryCount = 0;
                    state = State.COMPLETE;
                }
            } else {
                tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6253\u5f00RS\u7ec8\u7aef\u8d85\u65f6\uff0c\u5165\u5e93\u5931\u8d25"));
                state = State.CALCULATE;
            }
            return;
        }

        // Check if GUI is open
        if (isGridGuiOpen(player)) {
            if (fallbackTimer >= 2) {
                if (recoveryMode) {
                    AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Recovery: grid GUI stable, starting recovery deposit");
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6062\u590d\u6a21\u5f0f: \u901a\u8fc7RS\u7ec8\u7aef\u5b58\u5165\u76ee\u6807\u7269\u54c1..."));
                    state = State.RECOVERY_DEPOSIT;
                } else {
                    AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback: grid GUI stable, starting insert");
                    // Count items to deposit (only newly bought)
                    int itemCount = countNewlyBoughtItems(player);
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u901a\u8fc7RS\u7ec8\u7aef\u5165\u5e93\u4e2d... (\u5171" + itemCount + "\u4ef6\u7269\u54c1)"));
                    state = State.FALLBACK_INSERTING;
                }
                fallbackTimer = 0;
                fallbackCurrentSlot = 0;
            }
        }
    }

    /**
     * Count total newly bought items in inventory (items that weren't there before the buy).
     */
    private int countNewlyBoughtItems(LocalPlayer player) {
        int total = 0;
        // Track which item types we've already counted
        java.util.Set<String> countedTypes = new java.util.HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || RSNetworkHelper.isWirelessTerminal(slot)) continue;
            String itemKey = ForgeRegistries.ITEMS.getKey(slot.getItem()).toString();
            if (countedTypes.contains(itemKey)) continue;
            countedTypes.add(itemKey);
            int newlyBought = getNewlyBoughtCount(player, itemKey);
            if (newlyBought > 0) {
                total += newlyBought;
            }
        }
        return total;
    }

    /**
     * FALLBACK_INSERTING: Simulate shift+double-click to deposit only NEWLY BOUGHT items into RS grid.
     * Only deposits items that were acquired in this buy batch (compared against pre-buy snapshot).
     * After all packets sent, immediately transitions to FALLBACK_CLOSING.
     */
    private void handleFallbackInserting(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();

        // Check GUI is still open
        if (!isGridGuiOpen(player)) {
            AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Fallback: grid GUI closed during insert");
            tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] RS\u7ec8\u7aef\u754c\u9762\u610f\u5916\u5173\u95ed\uff0c\u5165\u5e93\u53ef\u80fd\u4e0d\u5b8c\u6574"));
            state = State.FALLBACK_CLOSING;
            fallbackTimer = 0;
            return;
        }

        // Phase 1: Scan inventory and find only newly bought items
        // Key: registry item ID, Value: list of inventory slot indices containing that item
        java.util.Map<String, java.util.List<Integer>> itemsToDeposit = new java.util.LinkedHashMap<>();
        int totalItemsToDeposit = 0;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack slotItem = player.getInventory().getItem(slot);
            if (slotItem.isEmpty() || RSNetworkHelper.isWirelessTerminal(slotItem)) {
                continue;
            }
            String itemKey = ForgeRegistries.ITEMS.getKey(slotItem.getItem()).toString();

            // Only deposit items that were newly bought (not pre-existing)
            int newlyBought = getNewlyBoughtCount(player, itemKey);
            if (newlyBought <= 0) {
                continue;
            }

            itemsToDeposit.computeIfAbsent(itemKey, k -> new java.util.ArrayList<>()).add(slot);
            totalItemsToDeposit += slotItem.getCount();
        }

        if (itemsToDeposit.isEmpty()) {
            // Nothing to deposit
            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback: no newly bought items to deposit, skipping");
            state = State.FALLBACK_CLOSING;
            fallbackTimer = 0;
            return;
        }

        // Phase 2: For each item type, send QUICK_MOVE for all slots of that type
        // Also track which client-side slots need to be cleared after deposit
        int insertedSlots = 0;
        int insertedItems = 0;
        java.util.List<Integer> depositedSlotIndices = new java.util.ArrayList<>();

        for (java.util.Map.Entry<String, java.util.List<Integer>> entry : itemsToDeposit.entrySet()) {
            String itemKey = entry.getKey();
            java.util.List<Integer> slots = entry.getValue();

            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback: deposit newly bought type={} across {} slots",
                    itemKey, slots.size());

            for (int slot : slots) {
                ItemStack slotItem = player.getInventory().getItem(slot);
                int countBefore = slotItem.getCount();

                // Dynamically find the correct container slot index in the open RS Grid
                int containerSlot = getContainerSlotIndex(player, slot);
                if (containerSlot < 0) {
                    AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Fallback: could not find container slot for inventory slot {}", slot);
                    continue;
                }

                // Use SHIFT+CLICK (QUICK_MOVE) to transfer item into RS grid
                mc.gameMode.handleInventoryMouseClick(
                        player.containerMenu.containerId,
                        containerSlot,
                        0,
                        ClickType.QUICK_MOVE,
                        player
                );

                totalDeposited += countBefore;
                insertedItems += countBefore;
                insertedSlots++;
                depositedSlotIndices.add(slot);
            }
        }

        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback: batch deposited {} items from {} slots ({} types)",
                insertedItems, insertedSlots, itemsToDeposit.size());
        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u5df2\u5165\u5e93 " + insertedItems + " \u4ef6 (" + itemsToDeposit.size() + " \u79cd)" + buildProgressInfo()));

        // Phase 3: Manually clear deposited items from client-side inventory.
        // Since the RS GUI deposit is client-side simulated (QUICK_MOVE packets),
        // the server processes the transfer but the client inventory isn't immediately
        // updated. Without this sync, calculateBatch would see stale "ghost" items
        // and over-estimate available capacity on the next round.
        for (int slotIdx : depositedSlotIndices) {
            player.getInventory().setItem(slotIdx, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Client inventory synced: cleared {} deposited slots", depositedSlotIndices.size());

        // Clear the snapshot after deposit
        inventoryBeforeBuy.clear();

        // No retry - go directly to closing
        state = State.FALLBACK_CLOSING;
        fallbackTimer = 0;
    }

    /**
     * FALLBACK_CLOSING: Close the grid GUI and restore state.
     * Waits for guiStayInterval ticks to ensure server sync before closing.
     * In recovery mode, re-checks inventory for empty slots and retries up to MAX_RECOVERY_RETRIES times.
     */
    private void handleFallbackClosing(LocalPlayer player) {
        fallbackTimer++;
        int stayInterval = Config.guiStayInterval;
        if (fallbackTimer >= stayInterval) {
            restoreFallbackState(player);
            useFallbackDeposit = false;

            if (recoveryMode) {
                recoveryMode = false;
                if (!recoveryDepositedAny) {
                    // Recovery deposit didn't actually deposit anything - avoid infinite loop
                    AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Recovery deposit failed to deposit any items, stopping");
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6062\u590d\u5165\u5e93\u5931\u8d25\uff0c\u7269\u54c1\u65e0\u6cd5\u5b58\u5165RS\u7ec8\u7aef"));
                    purchaseFrozen = false;
                    state = State.COMPLETE;
                    recoveryRetryCount = 0;
                } else {
                    AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Recovery deposit complete, re-checking inventory...");
                    tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u6062\u590d\u5165\u5e93\u5b8c\u6210\uff0c\u590d\u67e5\u80cc\u5305\u7a7a\u4f4d..."));
                    // Re-check if inventory now has empty slots
                    if (hasEmptyInventorySlot(player)) {
                        // Has empty slots - unfreeze and proceed with normal purchase
                        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Inventory has empty slots after recovery, unfreezing and resuming purchase");
                        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u80cc\u5305\u6709\u7a7a\u4f4d\uff0c\u89e3\u9664\u51bb\u7ed3\uff0c\u7ee7\u7eed\u8d2d\u4e70..."));
                        purchaseFrozen = false;
                        recoveryRetryCount = 0;
                        state = State.CALCULATE;
                    } else if (recoveryRetryCount < MAX_RECOVERY_RETRIES) {
                        // Still no empty slots - retry recovery deposit (purchase stays frozen)
                        recoveryRetryCount++;
                        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Still no empty slots after recovery, retry {}/{}",
                                recoveryRetryCount, MAX_RECOVERY_RETRIES);
                        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u80cc\u5305\u4ecd\u65e0\u7a7a\u4f4d\uff0c\u5c1d\u8bd5\u7b2c" + recoveryRetryCount + "/" + MAX_RECOVERY_RETRIES + "\u6b21\u6062\u590d\u5165\u5e93..."));
                        // Re-trigger recovery deposit
                        if (hasTargetItemsInInventory(player)) {
                            recoveryMode = true;
                            recoveryDepositedSlots.clear();
                            recoveryDepositedAny = false;
                            int terminalSlot = findWirelessTerminalInInventory(player);
                            if (terminalSlot < 0) terminalSlot = player.getInventory().selected;
                            startFallbackDeposit(player, terminalSlot);
                        } else {
                            // No more target items to deposit but still no space - stop
                            AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] No target items left to deposit but no empty slots, stopping");
                            tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u80cc\u5305\u5df2\u6ee1\u4e14\u65e0\u76ee\u6807\u7269\u54c1\u53ef\u5165\u5e93\uff0c\u505c\u6b62"));
                            purchaseFrozen = false;
                            recoveryRetryCount = 0;
                            state = State.COMPLETE;
                        }
                    } else {
                        // Max retries reached
                        AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] Max recovery retries ({}) reached, stopping", MAX_RECOVERY_RETRIES);
                        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u5df2\u8fbe\u5230\u6700\u5927\u6062\u590d\u91cd\u8bd5\u6b21\u6570(" + MAX_RECOVERY_RETRIES + ")\uff0c\u505c\u6b62"));
                        purchaseFrozen = false;
                        recoveryRetryCount = 0;
                        state = State.COMPLETE;
                    }
                }
                recoveryDepositedSlots.clear();
            } else {
                state = State.CALCULATE;
            }
            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback deposit complete (stayed {} ticks), totalDeposited={}", fallbackTimer, totalDeposited);
        }
    }

    /**
     * Check if the player's inventory has at least one empty slot (excluding terminal slot).
     */
    private boolean hasEmptyInventorySlot(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Restore player state after fallback deposit.
     */
    private void restoreFallbackState(LocalPlayer player) {
        // Close GUI
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        Minecraft.getInstance().setScreen(null);

        // Restore selected slot
        if (fallbackOriginalSelected >= 0) {
            player.getInventory().selected = fallbackOriginalSelected;
            fallbackOriginalSelected = -1;
        }
    }

    /**
     * Check if the RS Grid GUI is currently open.
     */
    private boolean isGridGuiOpen(LocalPlayer player) {
        if (player == null) return false;
        String menuClass = player.containerMenu.getClass().getName();
        return menuClass.contains("GridContainerMenu");
    }



    /**
     * Dynamically find the container slot index for a given player inventory slot
     * in the currently open container (e.g., RS GridContainerMenu).
     * This is necessary because different containers have different slot layouts -
     * the RS Grid adds filter/crafting slots before player inventory, shifting the
     * hotbar slots to a different container index than the standard 36+slot.
     */
    private int getContainerSlotIndex(LocalPlayer player, int inventorySlot) {
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            Slot s = player.containerMenu.slots.get(i);
            if (s.container == player.getInventory() && s.getContainerSlot() == inventorySlot) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Build progress info string with percentage and character progress bar.
     * Example: " 45% [████████░░░░░░░░░░░░]"
     */
    private String buildProgressInfo() {
        if (targetTotalCount <= 0) return "";
        int percent = Math.min(100, (int)((long)totalBought * 100 / targetTotalCount));
        int filledBars = percent / 5; // 20 bars total
        StringBuilder bar = new StringBuilder(" ");
        bar.append(percent).append("% [");
        for (int i = 0; i < 20; i++) {
            bar.append(i < filledBars ? '\u2588' : '\u2591');
        }
        bar.append("] ").append(totalBought).append("/").append(targetTotalCount);
        return bar.toString();
    }

    /**
     * Swap an inventory item to the hotbar using PICKUP clicks.
     */
    private void swapToHotbar(LocalPlayer player, int inventorySlot) {
        Minecraft mc = Minecraft.getInstance();
        int currentHotbar = player.getInventory().selected;

        // Step 1: Pick up the item from inventory
        mc.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId,
                inventorySlot,
                0, ClickType.PICKUP, player
        );

        // Step 2: Place it in the current hotbar slot
        // inventoryMenu uses direct inventory slot indices (0-8 = hotbar, 9-35 = main)
        mc.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId,
                currentHotbar,
                0, ClickType.PICKUP, player
        );

        // Step 3: If holding something (original hotbar item), put it back
        mc.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId,
                inventorySlot,
                0, ClickType.PICKUP, player
        );

        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Fallback: swapped slot {} to hotbar {}", inventorySlot, currentHotbar);
    }

    private void fail(String message) {
        state = State.FAILED;
        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u5931\u8d25: " + message));
        AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] {}", message);
    }

    private void abort(String message) {
        state = State.ABORTED;
        tellPlayer(Component.literal("[\u81ea\u52a8\u8d2d\u4e70] \u5df2\u4e2d\u65ad: " + message));
        AutoShopBuyerMod.LOGGER.warn("[AutoShopBuyer] {}", message);
    }

    private void tellPlayer(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(msg, false);
        }
    }

    // Getters
    public State getState() { return state; }
    public String getTargetItemName() { return targetItemName; }
    public int getTotalBought() { return totalBought; }
    public int getTotalDeposited() { return totalDeposited; }
    public int getTargetTotalCount() { return targetTotalCount; }
    public boolean isTerminal() {
        return state == State.COMPLETE || state == State.FAILED || state == State.ABORTED;
    }
}
