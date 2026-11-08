package com.leonimust.applecraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

public class AppleCraftFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(AppleCraftClient.KEY_CATEGORY_ID);
        AppleCraftClient.createKeys(category).forEach(KeyMappingHelper::registerKeyMapping);
        ClientTickEvents.END_CLIENT_TICK.register(AppleCraftClient::onClientTick);
        HudElementRegistry.addLast(NowPlayingHud.ID, NowPlayingHud::render);
        AppleCraftClient.init(FabricLoader.getInstance().getConfigDir());
    }
}
