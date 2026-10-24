package com.agen.autoshopbuyer;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(AutoShopBuyerMod.MODID)
public class AutoShopBuyerMod {
    public static final String MODID = "autoshopbuyer";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Keybind to open AutoShopBuyer GUI (default: B key)
    public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
            "key.autoshopbuyer.open_gui",
            GLFW.GLFW_KEY_B,
            "category.autoshopbuyer"
    );

    public AutoShopBuyerMod(FMLJavaModLoadingContext context) {
        // Register config
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register client setup
        context.getModEventBus().addListener(this::onClientSetup);

        // Register keybind
        context.getModEventBus().addListener(this::onRegisterKeyMappings);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[AutoShopBuyer] Client setup - initializing AutoShopBuyer manager");
        Config.load();
        Config.loadAgenConfig();

        // Register the AutoShopBuyer manager as an event subscriber
        MinecraftForge.EVENT_BUS.register(ShopBuyerManager.INSTANCE);

        // Register commands via RegisterCommandsEvent
        MinecraftForge.EVENT_BUS.register(new ShopBuyerManager.CommandRegistrar());

        // Register keybind tick handler on Forge event bus
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI_KEY);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (OPEN_GUI_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                mc.tell(() -> mc.setScreen(new ShopBuyerScreen()));
            }
        }
    }
}
