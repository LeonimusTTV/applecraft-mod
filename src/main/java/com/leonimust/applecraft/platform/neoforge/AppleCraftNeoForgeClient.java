package com.leonimust.applecraft.platform.neoforge;

//? if neoforge {
/*import com.leonimust.applecraft.Constants;
import com.leonimust.applecraft.client.AppleCraftClient;
import com.leonimust.applecraft.client.NowPlayingHud;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/^* NeoForge entrypoint, client only (dist = CLIENT). Only compiled in the NeoForge builds. *^/
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class AppleCraftNeoForgeClient {

    public AppleCraftNeoForgeClient(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            KeyMapping.Category category = new KeyMapping.Category(AppleCraftClient.KEY_CATEGORY_ID);
            event.registerCategory(category);
            AppleCraftClient.createKeys(category).forEach(event::register);
        });
        modBus.addListener(RegisterGuiLayersEvent.class, event -> event.registerAboveAll(NowPlayingHud.ID, NowPlayingHud::render));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> AppleCraftClient.onClientTick(Minecraft.getInstance()));
        AppleCraftClient.init(FMLPaths.CONFIGDIR.get());
    }
}
*///?}
