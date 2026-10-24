package com.agen.autoshopbuyer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Handles RS wireless terminal detection (client-side only).
 * No server-side communication needed - deposit is done via RS GUI fallback.
 */
public class RSNetworkHelper {
    // Wireless crafting grid item IDs from refinedstorageaddons
    private static final ResourceLocation WIRELESS_CRAFTING_GRID = new ResourceLocation("refinedstorageaddons", "wireless_crafting_grid");
    private static final ResourceLocation CREATIVE_WIRELESS_CRAFTING_GRID = new ResourceLocation("refinedstorageaddons", "creative_wireless_crafting_grid");

    /**
     * Check if an ItemStack is a wireless crafting grid (any type).
     */
    public static boolean isWirelessTerminal(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation regName = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (regName == null) return false;
        return regName.equals(WIRELESS_CRAFTING_GRID) || regName.equals(CREATIVE_WIRELESS_CRAFTING_GRID);
    }

    /**
     * Check if the player is holding a valid wireless crafting grid terminal.
     */
    public static boolean hasValidWirelessTerminal(LocalPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.isEmpty()) return false;
        if (!isWirelessTerminal(mainHand)) return false;
        return isNetworkItemValid(mainHand);
    }

    /**
     * Check if the NetworkItem has valid NBT data (NodeX, NodeY, NodeZ, Dimension).
     */
    public static boolean isNetworkItemValid(ItemStack stack) {
        if (!stack.hasTag()) return false;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        return tag.contains("NodeX") && tag.contains("NodeY") && tag.contains("NodeZ") && tag.contains("Dimension");
    }
}
