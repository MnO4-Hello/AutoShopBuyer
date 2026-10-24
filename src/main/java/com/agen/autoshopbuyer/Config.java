package com.agen.autoshopbuyer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.common.ForgeConfigSpec;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue BATCH_SIZE = BUILDER
            .comment("Maximum number of empty slots to fill per batch (controls how many buy transactions per round)")
            .defineInRange("batchSize", 36, 1, 36);

    public static final ForgeConfigSpec.IntValue TICKS_BETWEEN_BATCHES = BUILDER
            .comment("Ticks to wait between batches")
            .defineInRange("ticksBetweenBatches", 1, 1, 200);

    public static final ForgeConfigSpec.IntValue WAIT_ITEMS_TICKS = BUILDER
            .comment("Ticks to wait for items to arrive in inventory after buying")
            .defineInRange("waitItemsTicks", 3, 1, 60);

    public static final ForgeConfigSpec.DoubleValue MOVE_TOLERANCE = BUILDER
            .comment("How far the player can move before auto-buy is aborted (blocks)")
            .defineInRange("moveTolerance", 1.0, 0.1, 10.0);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int batchSize;
    public static int ticksBetweenBatches;
    public static int waitItemsTicks;
    public static double moveTolerance;

    // AGen file-based config values
    public static int defaultIntervalTicks = 10;
    public static int guiStayInterval = 5;
    public static int ticksBetweenBatchesFile = -1; // -1 = use Forge config value
    public static int waitItemsTicksFile = -1;       // -1 = use Forge config value

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File agenConfigFile;

    static void load() {
        batchSize = BATCH_SIZE.get();
        ticksBetweenBatches = TICKS_BETWEEN_BATCHES.get();
        waitItemsTicks = WAIT_ITEMS_TICKS.get();
        moveTolerance = MOVE_TOLERANCE.get();
    }

    /**
     * Initialize and load the AGen config file from config/AGen/autoshopbuyer.json
     */
    public static void loadAgenConfig() {
        try {
            // Find the config directory
            File configDir = new File("config/AGen");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            agenConfigFile = new File(configDir, "autoshopbuyer.json");

            if (agenConfigFile.exists()) {
                // Read existing config
                FileReader reader = new FileReader(agenConfigFile);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = GSON.fromJson(reader, Map.class);
                reader.close();

                if (data != null) {
                    if (data.containsKey("defaultIntervalTicks"))
                        defaultIntervalTicks = ((Number) data.get("defaultIntervalTicks")).intValue();
                    if (data.containsKey("guiStayInterval"))
                        guiStayInterval = ((Number) data.get("guiStayInterval")).intValue();
                    if (data.containsKey("ticksBetweenBatches"))
                        ticksBetweenBatchesFile = ((Number) data.get("ticksBetweenBatches")).intValue();
                    if (data.containsKey("waitItemsTicks"))
                        waitItemsTicksFile = ((Number) data.get("waitItemsTicks")).intValue();
                }
                AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] AGen config loaded: interval={}, guiStay={}, betweenBatches={}, waitItems={}",
                        defaultIntervalTicks, guiStayInterval, ticksBetweenBatchesFile, waitItemsTicksFile);
            } else {
                // Create default config file
                saveAgenConfig();
                AutoShopBuyerMod.LOGGER.info("[AutoShopBuyer] AGen config file created with defaults at config/AGen/autoshopbuyer.json");
            }

            // Apply file config to Forge config overrides
            if (ticksBetweenBatchesFile > 0) {
                ticksBetweenBatches = ticksBetweenBatchesFile;
            }
            if (waitItemsTicksFile > 0) {
                waitItemsTicks = waitItemsTicksFile;
            }
        } catch (Exception e) {
            AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] Failed to load AGen config", e);
        }
    }

    /**
     * Save current AGen config values to file.
     */
    public static void saveAgenConfig() {
        try {
            if (agenConfigFile == null) {
                File configDir = new File("config/AGen");
                if (!configDir.exists()) configDir.mkdirs();
                agenConfigFile = new File(configDir, "autoshopbuyer.json");
            }

            Map<String, Object> data = new HashMap<>();
            data.put("defaultIntervalTicks", defaultIntervalTicks);
            data.put("guiStayInterval", guiStayInterval);
            data.put("ticksBetweenBatches", ticksBetweenBatchesFile);
            data.put("waitItemsTicks", waitItemsTicksFile);

            FileWriter writer = new FileWriter(agenConfigFile);
            GSON.toJson(data, writer);
            writer.close();
        } catch (Exception e) {
            AutoShopBuyerMod.LOGGER.error("[AutoShopBuyer] Failed to save AGen config", e);
        }
    }

    /**
     * Reload config from file (used by /autobuy overload command).
     */
    public static void reloadAgenConfig() {
        // Reset to defaults first
        defaultIntervalTicks = 10;
        guiStayInterval = 5;
        ticksBetweenBatchesFile = -1;
        waitItemsTicksFile = -1;

        // Reload from Forge config
        load();

        // Reload from file
        loadAgenConfig();
    }

    /**
     * Get effective ticks between batches (file override or Forge config).
     */
    public static int getEffectiveTicksBetweenBatches() {
        return ticksBetweenBatchesFile > 0 ? ticksBetweenBatchesFile : ticksBetweenBatches;
    }

    /**
     * Get effective wait items ticks (file override or Forge config).
     */
    public static int getEffectiveWaitItemsTicks() {
        return waitItemsTicksFile > 0 ? waitItemsTicksFile : waitItemsTicks;
    }
}
