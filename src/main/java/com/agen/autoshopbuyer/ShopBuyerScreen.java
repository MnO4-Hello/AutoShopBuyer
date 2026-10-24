package com.agen.autoshopbuyer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * AutoShopBuyer GUI screen.
 * Shows all shop items in a scrollable list, allows selecting an item,
 * entering a target quantity, and starting the auto-buy task.
 */
public class ShopBuyerScreen extends Screen {

    // Layout constants
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 280;
    private static final int LIST_WIDTH = 240;
    private static final int ITEM_HEIGHT = 24;
    private static final int SEARCH_Y = 24;
    private static final int SEARCH_HEIGHT = 20;
    private static final int SCROLL_PANEL_Y = 48;
    private static final int SCROLL_PANEL_HEIGHT = 192;
    private static final int SCROLLBAR_WIDTH = 6;

    private int guiLeft, guiTop;

    // Shop data
    private final List<ShopEntryInfo> allEntries = new ArrayList<>();
    private final List<ShopEntryInfo> filteredEntries = new ArrayList<>();
    private int scrollOffset = 0;
    private int selectedIndex = -1; // index into filteredEntries
    private boolean draggingScrollbar = false;

    // Widgets
    private EditBox searchInput;
    private EditBox quantityInput;
    private EditBox intervalInput;
    private EditBox guiStayInput;
    private Button startButton;
    private Button stopButton;

    // Task status
    private String statusText = "";

    public ShopBuyerScreen() {
        super(Component.literal("\u81ea\u52a8\u8d2d\u4e70"));
        loadShopEntries();
    }

    @Override
    protected void init() {
        super.init();
        guiLeft = (this.width - PANEL_WIDTH) / 2;
        guiTop = (this.height - PANEL_HEIGHT) / 2;

        // Search input box (above item list)
        searchInput = new EditBox(this.font,
                guiLeft + 8, guiTop + SEARCH_Y, LIST_WIDTH - 12, SEARCH_HEIGHT,
                Component.literal("\u641c\u7d22"));
        searchInput.setMaxLength(50);
        searchInput.setTextColor(0xFFFFFF);
        searchInput.setTextColorUneditable(0x999999);
        searchInput.setResponder(text -> {
            applyFilter();
        });
        this.addRenderableWidget(searchInput);

        // Quantity input
        quantityInput = new EditBox(this.font,
                guiLeft + LIST_WIDTH + 30, guiTop + 60, 100, 18,
                Component.literal("\u6570\u91cf"));
        quantityInput.setFilter(s -> {
            if (s.isEmpty()) return true;
            try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
        });
        quantityInput.setValue("64");
        this.addRenderableWidget(quantityInput);

        // Interval input (ticks between buy and deposit)
        intervalInput = new EditBox(this.font,
                guiLeft + LIST_WIDTH + 30, guiTop + 95, 100, 18,
                Component.literal("\u95f4\u9694"));
        intervalInput.setFilter(s -> {
            if (s.isEmpty()) return true;
            try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
        });
        intervalInput.setValue(String.valueOf(ShopBuyerManager.INSTANCE.getDefaultInterval()));
        this.addRenderableWidget(intervalInput);

        // GUI stay interval input (ticks to keep RS GUI open before closing)
        guiStayInput = new EditBox(this.font,
                guiLeft + LIST_WIDTH + 30, guiTop + 130, 100, 18,
                Component.literal("GUI\u505c\u7559"));
        guiStayInput.setFilter(s -> {
            if (s.isEmpty()) return true;
            try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
        });
        guiStayInput.setValue(String.valueOf(ShopBuyerManager.INSTANCE.getGuiStayInterval()));
        this.addRenderableWidget(guiStayInput);

        // Start button
        startButton = Button.builder(
                Component.literal("\u5f00\u59cb"),
                btn -> doStart()
        ).bounds(guiLeft + LIST_WIDTH + 30, guiTop + 155, 60, 18).build();
        this.addRenderableWidget(startButton);

        // Stop button
        stopButton = Button.builder(
                Component.literal("\u505c\u6b62"),
                btn -> doStop()
        ).bounds(guiLeft + LIST_WIDTH + 100, guiTop + 155, 60, 18).build();
        this.addRenderableWidget(stopButton);

        updateButtonStates();
    }

    private void doStart() {
        String itemName = getSelectedName();
        if (itemName == null) {
            statusText = "\u8bf7\u5148\u9009\u62e9\u4e00\u4e2a\u7269\u54c1\uff01";
            return;
        }
        int count;
        try {
            count = Integer.parseInt(quantityInput.getValue());
        } catch (NumberFormatException e) {
            statusText = "\u6570\u91cf\u65e0\u6548\uff01";
            return;
        }
        if (count <= 0) {
            statusText = "\u6570\u91cf\u5fc5\u987b\u5927\u4e8e0\uff01";
            return;
        }
        int interval;
        try {
            interval = Integer.parseInt(intervalInput.getValue());
        } catch (NumberFormatException e) {
            statusText = "\u95f4\u9694\u65e0\u6548\uff01";
            return;
        }
        if (interval <= 0) {
            statusText = "\u95f4\u9694\u5fc5\u987b\u5927\u4e8e0\uff01";
            return;
        }
        int guiStay;
        try {
            guiStay = Integer.parseInt(guiStayInput.getValue());
        } catch (NumberFormatException e) {
            statusText = "GUI\u505c\u7559\u65e0\u6548\uff01";
            return;
        }
        if (guiStay <= 0) {
            statusText = "GUI\u505c\u7559\u5fc5\u987b\u5927\u4e8e0\uff01";
            return;
        }

        // Set the GUI stay interval before starting task
        ShopBuyerManager.INSTANCE.setGuiStayInterval(guiStay);
        ShopBuyerManager.INSTANCE.startTask(itemName, count, interval);
        // Close GUI so auto-buy can work
        this.onClose();
    }

    private void doStop() {
        ShopBuyerManager.INSTANCE.stopTask();
        statusText = "\u4efb\u52a1\u5df2\u505c\u6b62";
        updateButtonStates();
    }

    private String getSelectedName() {
        if (selectedIndex < 0 || selectedIndex >= filteredEntries.size()) return null;
        return filteredEntries.get(selectedIndex).displayName;
    }

    private void updateButtonStates() {
        boolean running = ShopBuyerManager.INSTANCE.isTaskRunning();
        if (startButton != null) startButton.active = !running;
        if (stopButton != null) stopButton.active = running;
    }

    // ===================== Rendering =====================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark background overlay
        this.renderBackground(guiGraphics);

        // Main panel background
        guiGraphics.fill(guiLeft, guiTop, guiLeft + PANEL_WIDTH, guiTop + PANEL_HEIGHT, 0xCC222222);
        guiGraphics.fill(guiLeft, guiTop, guiLeft + PANEL_WIDTH, guiTop + 1, 0xFF555555);
        guiGraphics.fill(guiLeft, guiTop + PANEL_HEIGHT - 1, guiLeft + PANEL_WIDTH, guiTop + PANEL_HEIGHT, 0xFF555555);
        guiGraphics.fill(guiLeft, guiTop, guiLeft + 1, guiTop + PANEL_HEIGHT, 0xFF555555);
        guiGraphics.fill(guiLeft + PANEL_WIDTH - 1, guiTop, guiLeft + PANEL_WIDTH, guiTop + PANEL_HEIGHT, 0xFF555555);

        // Title
        guiGraphics.drawString(this.font, "\u81ea\u52a8\u8d2d\u4e70 - \u5546\u5e97\u81ea\u52a8\u91c7\u8d2d\u7cfb\u7edf",
                guiLeft + 10, guiTop + 10, 0xFFFFFF);

        // Search box background (make it clearly visible)
        guiGraphics.fill(guiLeft + 6, guiTop + SEARCH_Y - 2,
                guiLeft + LIST_WIDTH - 2, guiTop + SEARCH_Y + SEARCH_HEIGHT + 2, 0xFF000000);
        guiGraphics.fill(guiLeft + 6, guiTop + SEARCH_Y - 2,
                guiLeft + LIST_WIDTH - 2, guiTop + SEARCH_Y - 1, 0xFF666666);
        guiGraphics.fill(guiLeft + 6, guiTop + SEARCH_Y + SEARCH_HEIGHT + 1,
                guiLeft + LIST_WIDTH - 2, guiTop + SEARCH_Y + SEARCH_HEIGHT + 2, 0xFF666666);
        guiGraphics.fill(guiLeft + 6, guiTop + SEARCH_Y - 2,
                guiLeft + 7, guiTop + SEARCH_Y + SEARCH_HEIGHT + 2, 0xFF666666);
        guiGraphics.fill(guiLeft + LIST_WIDTH - 3, guiTop + SEARCH_Y - 2,
                guiLeft + LIST_WIDTH - 2, guiTop + SEARCH_Y + SEARCH_HEIGHT + 2, 0xFF666666);

        // Item list area background
        guiGraphics.fill(guiLeft + 5, guiTop + SCROLL_PANEL_Y,
                guiLeft + 5 + LIST_WIDTH, guiTop + SCROLL_PANEL_Y + SCROLL_PANEL_HEIGHT, 0xAA111111);
        guiGraphics.drawString(this.font, "\u5546\u54c1\u5217\u8868 (\u5171" + filteredEntries.size() + "\u4ef6):", guiLeft + LIST_WIDTH + 2, guiTop + 14, 0xAAAAAA);

        // Render item list
        renderItemList(guiGraphics, mouseX, mouseY);

        // Right panel labels
        int rightX = guiLeft + LIST_WIDTH + 20;
        guiGraphics.drawString(this.font, "\u8d2d\u4e70\u6570\u91cf:", rightX, guiTop + 48, 0xAAAAAA);
        guiGraphics.drawString(this.font, "\u5165\u5e93\u95f4\u9694(t):", rightX, guiTop + 83, 0xAAAAAA);
        guiGraphics.drawString(this.font, "GUI\u505c\u7559(t):", rightX, guiTop + 118, 0xAAAAAA);

        // Selected item display
        if (selectedIndex >= 0 && selectedIndex < filteredEntries.size()) {
            ShopEntryInfo info = filteredEntries.get(selectedIndex);
            guiGraphics.drawString(this.font, "\u5df2\u9009\u62e9:", rightX, guiTop + 180, 0xAAAAAA);
            // Draw item icon + name
            guiGraphics.renderItem(info.itemStack, rightX, guiTop + 192);
            guiGraphics.drawString(this.font, info.displayName, rightX + 20, guiTop + 197, 0x55FF55);
            guiGraphics.drawString(this.font,
                    "\u6bcf\u6b21: " + info.entryCount + " | \u4ef7\u683c: " + info.price,
                    rightX, guiTop + 212, 0x888888);
        }

        // Status
        guiGraphics.drawString(this.font, statusText,
                guiLeft + 10, guiTop + PANEL_HEIGHT - 20, 0xFFFF55);

        // Task status
        if (ShopBuyerManager.INSTANCE.isTaskRunning()) {
            var task = ShopBuyerManager.INSTANCE.getCurrentTask();
            String taskInfo = String.format("\u72b6\u6001: %s | \u5df2\u8d2d\u4e70: %d%s",
                    task.getState(), task.getTotalBought(),
                    task.getTargetTotalCount() > 0 ? ("/" + task.getTargetTotalCount()) : "");
            guiGraphics.drawString(this.font, taskInfo,
                    guiLeft + 10, guiTop + PANEL_HEIGHT - 10, 0x55FFFF);
        }

        // Render widgets (buttons, text field)
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw placeholder text AFTER EditBox renders, only when search input is empty and not focused
        if (searchInput != null && searchInput.getValue().isEmpty() && !searchInput.isFocused()) {
            guiGraphics.drawString(this.font, "\u641c\u7d22\u7269\u54c1\u540d\u79f0...",
                    guiLeft + 12, guiTop + SEARCH_Y + 6, 0xFF888888);
        }

        updateButtonStates();
    }

    private void renderItemList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int listX = guiLeft + 6;
        int listY = guiTop + SCROLL_PANEL_Y + 1;
        int listW = LIST_WIDTH - 2 - SCROLLBAR_WIDTH; // leave space for scrollbar
        int listH = SCROLL_PANEL_HEIGHT - 2;

        // Enable scissor clipping for the list area
        guiGraphics.enableScissor(listX, listY, listX + listW, listY + listH);

        for (int i = 0; i < filteredEntries.size(); i++) {
            int drawY = listY + (i - scrollOffset) * ITEM_HEIGHT;
            if (drawY + ITEM_HEIGHT < listY || drawY > listY + listH) continue;

            boolean isSelected = (i == selectedIndex);
            boolean isHovered = isMouseOver(mouseX, mouseY, listX, drawY, listW, ITEM_HEIGHT);

            int bgColor = isSelected ? 0xAA335533 : (isHovered ? 0xAA333355 : 0x00000000);
            if (bgColor != 0) {
                guiGraphics.fill(listX, drawY, listX + listW, drawY + ITEM_HEIGHT - 1, bgColor);
            }

            ShopEntryInfo info = filteredEntries.get(i);

            // Render item icon
            guiGraphics.renderItem(info.itemStack, listX + 2, drawY + 2);

            // Render item name
            int nameColor = isSelected ? 0x55FF55 : 0xDDDDDD;
            guiGraphics.drawString(this.font, info.displayName,
                    listX + 22, drawY + 7, nameColor);

            // Render count info on the right
            guiGraphics.drawString(this.font, "x" + info.entryCount,
                    listX + listW - 30, drawY + 7, 0x888888);
        }

        guiGraphics.disableScissor();

        // Scrollbar track + thumb
        if (filteredEntries.size() > getVisibleCount()) {
            int scrollBarX = guiLeft + 5 + LIST_WIDTH - SCROLLBAR_WIDTH;
            int scrollBarTrackY = guiTop + SCROLL_PANEL_Y;
            int scrollBarH = SCROLL_PANEL_HEIGHT;

            // Draw track background
            guiGraphics.fill(scrollBarX, scrollBarTrackY, scrollBarX + SCROLLBAR_WIDTH,
                    scrollBarTrackY + scrollBarH, 0xFF333333);

            // Draw thumb
            int thumbH = Math.max(15, scrollBarH * getVisibleCount() / filteredEntries.size());
            int maxScroll = Math.max(1, filteredEntries.size() - getVisibleCount());
            int thumbY = scrollBarTrackY + (scrollBarH - thumbH) * scrollOffset / maxScroll;
            boolean hoverThumb = isMouseOver(mouseX, mouseY, scrollBarX, thumbY, SCROLLBAR_WIDTH, thumbH);
            guiGraphics.fill(scrollBarX, thumbY, scrollBarX + SCROLLBAR_WIDTH,
                    thumbY + thumbH, hoverThumb || draggingScrollbar ? 0xFFCCCCCC : 0xFF999999);
        }
    }

    private int getVisibleCount() {
        return SCROLL_PANEL_HEIGHT / ITEM_HEIGHT;
    }

    // ===================== Input Handling =====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, filteredEntries.size() - getVisibleCount());
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else if (delta < 0) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check scrollbar click first (higher priority than item selection)
        int scrollBarX = guiLeft + 5 + LIST_WIDTH - SCROLLBAR_WIDTH;
        int scrollBarTrackY = guiTop + SCROLL_PANEL_Y;
        if (filteredEntries.size() > getVisibleCount()
                && isMouseOver(mouseX, mouseY, scrollBarX, scrollBarTrackY, SCROLLBAR_WIDTH, SCROLL_PANEL_HEIGHT)) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true; // consume the click, don't select items
        }

        // Check item list click (exclude scrollbar area)
        int listX = guiLeft + 6;
        int listY = guiTop + SCROLL_PANEL_Y + 1;
        int listW = LIST_WIDTH - 2 - SCROLLBAR_WIDTH;
        int listH = SCROLL_PANEL_HEIGHT - 2;

        if (isMouseOver(mouseX, mouseY, listX, listY, listW, listH)) {
            int relY = (int) mouseY - listY;
            int clickedIndex = scrollOffset + relY / ITEM_HEIGHT;
            if (clickedIndex >= 0 && clickedIndex < filteredEntries.size()) {
                selectedIndex = clickedIndex;
                statusText = "\u5df2\u9009\u62e9: " + filteredEntries.get(clickedIndex).displayName;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Update scroll offset based on mouse Y position on the scrollbar track.
     */
    private void updateScrollFromMouse(double mouseY) {
        int scrollBarTrackY = guiTop + SCROLL_PANEL_Y;
        int scrollBarH = SCROLL_PANEL_HEIGHT;
        int maxScroll = Math.max(1, filteredEntries.size() - getVisibleCount());
        int thumbH = Math.max(15, scrollBarH * getVisibleCount() / filteredEntries.size());
        int usableHeight = scrollBarH - thumbH;
        if (usableHeight <= 0) return;

        double relY = mouseY - scrollBarTrackY - thumbH / 2.0;
        double ratio = relY / usableHeight;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        scrollOffset = (int) Math.round(ratio * maxScroll);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            if (searchInput.isFocused()) {
                // First ESC unfocuses search box, second ESC closes screen
                searchInput.setFocused(false);
                return true;
            }
            this.onClose();
            return true;
        }
        // Enter to start (only from quantity/interval/guiStay inputs, not search)
        if (keyCode == 257 && (quantityInput.isFocused() || intervalInput.isFocused() || guiStayInput.isFocused())) { // ENTER
            doStart();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===================== Search Filter =====================

    /**
     * Apply search filter to the item list based on current search text.
     * Supports Chinese character matching in real-time.
     */
    private void applyFilter() {
        String query = searchInput.getValue().trim().toLowerCase();
        filteredEntries.clear();
        selectedIndex = -1;
        scrollOffset = 0;

        if (query.isEmpty()) {
            filteredEntries.addAll(allEntries);
        } else {
            for (ShopEntryInfo entry : allEntries) {
                if (entry.displayName.toLowerCase().contains(query)
                        || entry.registryName.toLowerCase().contains(query)) {
                    filteredEntries.add(entry);
                }
            }
        }
    }

    // ===================== Shop Data Loading =====================

    private void loadShopEntries() {
        allEntries.clear();
        try {
            Class<?> shopBaseClass = Class.forName("net.sixik.sdmshoprework.common.shop.ShopBase");
            Object clientShop = shopBaseClass.getField("CLIENT").get(null);
            if (clientShop == null) return;

            List<?> tabs = (List<?>) shopBaseClass.getMethod("getShopTabs").invoke(clientShop);
            if (tabs == null) return;

            for (Object tab : tabs) {
                Field tabUUIDField = tab.getClass().getSuperclass().getField("shopTabUUID");
                java.util.UUID tabUUID = (java.util.UUID) tabUUIDField.get(tab);

                Method getTabEntryMethod = tab.getClass().getMethod("getTabEntry");
                List<?> entries = (List<?>) getTabEntryMethod.invoke(tab);
                if (entries == null) continue;

                for (Object entry : entries) {
                    Object entryType = entry.getClass().getMethod("getEntryType").invoke(entry);
                    if (entryType == null) continue;

                    String typeName = entryType.getClass().getName();
                    if (!typeName.endsWith("ShopItemEntryType")) continue;

                    Field itemStackField = entryType.getClass().getField("itemStack");
                    ItemStack itemStack = (ItemStack) itemStackField.get(entryType);
                    if (itemStack == null || itemStack.isEmpty()) continue;

                    Field entryUUIDField = entry.getClass().getField("entryUUID");
                    java.util.UUID entryUUID = (java.util.UUID) entryUUIDField.get(entry);

                    Field entryCountField = entry.getClass().getField("entryCount");
                    int entryCount = (int) entryCountField.get(entry);

                    Field entryPriceField = entry.getClass().getField("entryPrice");
                    long price = (long) entryPriceField.get(entry);

                    String displayName = itemStack.getHoverName().getString();
                    String registryName = "";
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                    if (key != null) registryName = key.toString();

                    allEntries.add(new ShopEntryInfo(
                            tabUUID, entryUUID, itemStack.copy(),
                            displayName, registryName, entryCount, price
                    ));
                }
            }
            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Loaded {} shop entries", allEntries.size());
            // Initialize filtered list with all entries
            filteredEntries.clear();
            filteredEntries.addAll(allEntries);
        } catch (Exception e) {
            AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] Failed to load shop entries", e);
        }
    }

    // ===================== Helpers =====================

    private static boolean isMouseOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /**
     * Holds information about a single shop item entry.
     */
    public static class ShopEntryInfo {
        public final java.util.UUID tabUUID;
        public final java.util.UUID entryUUID;
        public final ItemStack itemStack;
        public final String displayName;
        public final String registryName;
        public final int entryCount;
        public final long price;

        public ShopEntryInfo(java.util.UUID tabUUID, java.util.UUID entryUUID,
                             ItemStack itemStack, String displayName, String registryName,
                             int entryCount, long price) {
            this.tabUUID = tabUUID;
            this.entryUUID = entryUUID;
            this.itemStack = itemStack;
            this.displayName = displayName;
            this.registryName = registryName;
            this.entryCount = entryCount;
            this.price = price;
        }
    }
}
