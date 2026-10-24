package com.agen.autoshopbuyer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Manages the auto-buy task lifecycle.
 * Handles Forge events (client tick, command registration).
 */
public class ShopBuyerManager {
    public static final ShopBuyerManager INSTANCE = new ShopBuyerManager();

    private ShopBuyerTask currentTask;
    private int delayCounter; // ticks to wait between batches
    private int defaultIntervalTicks = -1; // -1 = use Config value
    private int guiStayInterval = -1; // -1 = use Config value

    private ShopBuyerManager() {}

    /**
     * Check if a task is currently running.
     */
    public boolean isTaskRunning() {
        return currentTask != null && !currentTask.isTerminal();
    }

    /**
     * Get the current task (may be null).
     */
    public ShopBuyerTask getCurrentTask() {
        return currentTask;
    }

    /**
     * Start a new auto-buy task for the given item name.
     */
    public void startTask(String itemName) {
        startTask(itemName, 0);
    }

    /**
     * Start a new auto-buy task for the given item name with a target count.
     * @param targetCount 0 = unlimited
     */
    public void startTask(String itemName, int targetCount) {
        startTask(itemName, targetCount, getDefaultInterval());
    }

    /**
     * Start a new auto-buy task for the given item name with a target count and interval.
     * @param targetCount 0 = unlimited
     * @param intervalTicks ticks to wait between buy and deposit
     */
    public void startTask(String itemName, int targetCount, int intervalTicks) {
        if (currentTask != null && !currentTask.isTerminal()) {
            tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u5df2\u6709\u4efb\u52a1\u8fd0\u884c\u4e2d\uff01\u4f7f\u7528 /autobuy stop \u53d6\u6d88\u3002");
            return;
        }
        currentTask = new ShopBuyerTask(itemName, targetCount, intervalTicks);
        delayCounter = 0;
        String countStr = targetCount > 0 ? ("(\u76ee\u6807: " + targetCount + ")") : "";
        tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u5f00\u59cb\u81ea\u52a8\u8d2d\u4e70: " + itemName + countStr + " \u95f4\u9694=" + intervalTicks + "t, GUI\u505c\u7559=" + getGuiStayInterval() + "t");
        AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Task started for item: {}, targetCount: {}, intervalTicks: {}, guiStayInterval: {}",
                itemName, targetCount, intervalTicks, getGuiStayInterval());
    }

    /**
     * Open the AutoShopBuyer GUI screen.
     */
    public void openGui() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.tell(() -> mc.setScreen(new ShopBuyerScreen()));
        }
    }

    /**
     * Stop the current task.
     */
    public void stopTask() {
        if (currentTask == null || currentTask.isTerminal()) {
            tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u6ca1\u6709\u8fd0\u884c\u4e2d\u7684\u4efb\u52a1\u3002");
            return;
        }
        currentTask = null;
        tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u4efb\u52a1\u5df2\u505c\u6b62\u3002");
    }

    /**
     * Get current task status.
     */
    public void showStatus() {
        if (currentTask == null || currentTask.isTerminal()) {
            tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u6ca1\u6709\u6d3b\u8dc3\u4efb\u52a1\u3002");
            return;
        }
        tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u4efb\u52a1: " + currentTask.getTargetItemName()
                + " | \u72b6\u6001: " + currentTask.getState()
                + " | \u5df2\u8d2d\u4e70: " + currentTask.getTotalBought()
                + (currentTask.getTargetTotalCount() > 0 ? ("/" + currentTask.getTargetTotalCount()) : "")
                + " | \u5df2\u5165\u5e93: " + currentTask.getTotalDeposited());
    }

    /**
     * Set the default interval ticks for new tasks.
     */
    public void setDefaultInterval(int ticks) {
        this.defaultIntervalTicks = Math.max(1, ticks);
        Config.defaultIntervalTicks = this.defaultIntervalTicks;
        Config.saveAgenConfig();
        tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u9ed8\u8ba4\u5165\u5e93\u95f4\u9694\u5df2\u8bbe\u7f6e\u4e3a: " + defaultIntervalTicks + " tick");
    }

    /**
     * Get the default interval ticks.
     */
    public int getDefaultInterval() {
        return defaultIntervalTicks > 0 ? defaultIntervalTicks : Config.defaultIntervalTicks;
    }

    /**
     * Set the GUI stay interval (how long RS GUI stays open before closing).
     */
    public void setGuiStayInterval(int ticks) {
        this.guiStayInterval = Math.max(1, ticks);
        Config.guiStayInterval = this.guiStayInterval;
        Config.saveAgenConfig();
        tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] RS GUI\u505c\u7559\u95f4\u9694\u5df2\u8bbe\u7f6e\u4e3a: " + guiStayInterval + " tick");
    }

    /**
     * Get the GUI stay interval.
     */
    public int getGuiStayInterval() {
        return guiStayInterval > 0 ? guiStayInterval : Config.guiStayInterval;
    }

    /**
     * Reload config from file.
     */
    public void reloadConfig() {
        Config.reloadAgenConfig();
        // Reset runtime overrides so config file values take effect
        defaultIntervalTicks = -1;
        guiStayInterval = -1;
        tellPlayer("[\u81ea\u52a8\u8d2d\u4e70] \u914d\u7f6e\u5df2\u91cd\u8f7d! \u95f4\u9694=" + Config.defaultIntervalTicks
                + "t, GUI\u505c\u7559=" + Config.guiStayInterval
                + "t, \u6279\u6b21\u95f4\u9694=" + Config.getEffectiveTicksBetweenBatches()
                + "t, \u7b49\u5f85\u7269\u54c1=" + Config.getEffectiveWaitItemsTicks() + "t");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        if (currentTask == null || currentTask.isTerminal()) {
            // Clean up completed tasks
            if (currentTask != null && currentTask.isTerminal()) {
                AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Task ended. State: {}, Bought: {}",
                        currentTask.getState(), currentTask.getTotalBought());
                currentTask = null;
            }
            return;
        }

        // Handle delay between batches
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // Tick the task
        currentTask.tick();

        // If task transitioned to CALCULATE after DEPOSITING, add delay
        if (currentTask.getState() == ShopBuyerTask.State.CALCULATE) {
            delayCounter = Config.getEffectiveTicksBetweenBatches();
        }
    }

    private void tellPlayer(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(message), false);
        }
    }

    /**
     * Inner class to handle client command registration.
     */
    public static class CommandRegistrar {
        @SubscribeEvent
        public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

            dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("autobuy")
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("start")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("item", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String itemName = StringArgumentType.getString(ctx, "item");
                                        INSTANCE.startTask(itemName, 0, INSTANCE.getDefaultInterval());
                                        return 1;
                                    })
                            )
                            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("count")
                                    // Reordered: count <number> <item...> so item can contain colons (e.g. minecraft:quartz)
                                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("count", IntegerArgumentType.integer(1))
                                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("item", StringArgumentType.greedyString())
                                                    .executes(ctx -> {
                                                        int count = IntegerArgumentType.getInteger(ctx, "count");
                                                        String itemName = StringArgumentType.getString(ctx, "item");
                                                        INSTANCE.startTask(itemName, count, INSTANCE.getDefaultInterval());
                                                        return 1;
                                                    })
                                            )
                                    )
                            )
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("stop")
                            .executes(ctx -> {
                                INSTANCE.stopTask();
                                return 1;
                            })
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                            .executes(ctx -> {
                                INSTANCE.showStatus();
                                return 1;
                            })
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("gui")
                            .executes(ctx -> {
                                INSTANCE.openGui();
                                return 1;
                            })
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("interval")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("ticks", IntegerArgumentType.integer(1, 200))
                                    .executes(ctx -> {
                                        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                        INSTANCE.setDefaultInterval(ticks);
                                        return 1;
                                    })
                            )
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("guistay")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("ticks", IntegerArgumentType.integer(1, 200))
                                    .executes(ctx -> {
                                        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                        INSTANCE.setGuiStayInterval(ticks);
                                        return 1;
                                    })
                            )
                    )
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("overload")
                            .executes(ctx -> {
                                INSTANCE.reloadConfig();
                                return 1;
                            })
                    )
            );

            AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] Client commands registered: /autobuy start|stop|status|gui|interval|guistay|overload");
        }
    }
}
