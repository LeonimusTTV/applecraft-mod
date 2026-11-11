package com.leonimust.applecraft.client;

import com.leonimust.applecraft.Constants;
import com.leonimust.applecraft.bridge.BridgeConfig;
import com.leonimust.applecraft.bridge.Browser;
import com.leonimust.applecraft.bridge.Models;
import com.leonimust.applecraft.bridge.PlayerBridge;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Client code shared by Fabric and NeoForge. Loaders call init(), createKeys() and onClientTick(). */
public final class AppleCraftClient {
    private AppleCraftClient() {}

    public static final Identifier KEY_CATEGORY_ID = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "main");

    private static PlayerBridge bridge;

    public static KeyMapping OPEN_SCREEN;
    public static KeyMapping PLAY_PAUSE;
    public static KeyMapping NEXT;
    public static KeyMapping PREV;

    private static String lastTrackId;

    public static PlayerBridge bridge() {
        return bridge;
    }

    /**
     * @param configDir config/ folder provided by the loader. On NeoForge, init() runs during mod construction,
     *                  BEFORE Minecraft.getInstance() exists, so it must not be touched here.
     */
    public static void init(Path configDir) {
        BridgeConfig config = BridgeConfig.load(configDir.resolve("applecraft.json"));
        bridge = new PlayerBridge(config, Browser::open);
        bridge.onState(AppleCraftClient::onState);
        bridge.onStatus(s -> Constants.LOG.info("Player status: server={} page={}", s.serverUp(), s.playerUp()));
        bridge.start();
    }

    /** The category is created by the loader (Fabric: Category.register, NeoForge: event.registerCategory). */
    public static List<KeyMapping> createKeys(KeyMapping.Category category) {
        OPEN_SCREEN = new KeyMapping("key.applecraft.open", InputConstants.KEY_N, category);
        PLAY_PAUSE = new KeyMapping("key.applecraft.play_pause", InputConstants.KEY_K, category);
        NEXT = new KeyMapping("key.applecraft.next", InputConstants.KEY_RIGHT, category);
        PREV = new KeyMapping("key.applecraft.prev", InputConstants.KEY_LEFT, category);
        return List.of(OPEN_SCREEN, PLAY_PAUSE, NEXT, PREV);
    }

    /** Screen currently shown. It moved from Minecraft to Gui in 26.2. */
    public static Screen currentScreen(Minecraft mc) {
        //? if >=26.2 {
        return mc.gui.screen();
        //?} else {
        /*return mc.screen;
        *///?}
    }

    public static void openScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        mc.gui.setScreen(screen);
        //?} else {
        /*mc.setScreen(screen);
        *///?}
    }

    public static void onClientTick(Minecraft mc) {
        if (OPEN_SCREEN == null || bridge == null) return;
        while (OPEN_SCREEN.consumeClick()) openScreen(mc, new AppleCraftScreen());
        while (PLAY_PAUSE.consumeClick()) run(bridge::toggle);
        while (NEXT.consumeClick()) run(bridge::next);
        while (PREV.consumeClick()) run(bridge::prev);
    }

    private static void run(Supplier<? extends CompletableFuture<?>> action) {
        action.get().exceptionally(err -> {
            String code = AppleCraftScreen.errorCode(err);
            if ("unknown".equals(code)) Constants.LOG.warn("AppleCraft command failed", err);
            overlay(Component.literal(AppleCraftScreen.errorText(code)).withStyle(ChatFormatting.RED));
            return null;
        });
    }

    /** Shows the track above the hotbar when it changes (outside the AppleCraft screen). */
    private static void onState(Models.State s) {
        String id = s.item() != null ? s.item().id() : null;
        if (Objects.equals(id, lastTrackId)) return;
        lastTrackId = id;
        if (s.item() == null || !bridge.config().showTrackChangeMessage) return;
        overlay(Component.literal("♪ ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(s.item().name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" — " + s.item().artist()).withStyle(ChatFormatting.GRAY)));
    }

    /** Message above the hotbar, always on the main thread. */
    private static void overlay(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null && !(currentScreen(mc) instanceof AppleCraftScreen)) mc.player.sendOverlayMessage(msg);
        });
    }
}
