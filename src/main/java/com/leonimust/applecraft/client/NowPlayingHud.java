package com.leonimust.applecraft.client;

import com.leonimust.applecraft.bridge.BridgeConfig;
import com.leonimust.applecraft.bridge.Models;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * "Now playing" card in the HUD: artwork, title, artist, progress.
 * Registered as a HUD layer by each loader (Fabric: HudElementRegistry, NeoForge: RegisterGuiLayersEvent).
 */
public final class NowPlayingHud {
    private NowPlayingHud() {}

    public static final Identifier ID = Identifier.fromNamespaceAndPath(com.leonimust.applecraft.Constants.MOD_ID, "now_playing");

    private static final int W = 150, H = 34, MARGIN = 4, ART = 26;

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (AppleCraftClient.bridge() == null || mc.player == null) return;
        BridgeConfig cfg = AppleCraftClient.bridge().config();
        if (!cfg.showNowPlaying || mc.getDebugOverlay().showDebugScreen()) return;
        if (AppleCraftClient.currentScreen(mc) instanceof AppleCraftScreen) return; // the screen already has its own player bar

        Models.State s = AppleCraftClient.bridge().state();
        if (s == null || s.item() == null || !AppleCraftClient.bridge().status().playerUp()) return;

        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int x = cfg.nowPlayingPosition.endsWith("RIGHT") ? sw - W - MARGIN : MARGIN;
        int y = cfg.nowPlayingPosition.startsWith("BOTTOM") ? sh - H - MARGIN : MARGIN;
        // At the bottom, if the window is too narrow to fit next to the hotbar (182 px), move above it
        if (cfg.nowPlayingPosition.startsWith("BOTTOM") && sw < 182 + 2 * (W + MARGIN)) y -= 24;

        int alpha = s.isPlaying() ? 0xFF : 0xA0; // paused: slightly transparent
        g.fill(x, y, x + W, y + H, (Math.min(alpha, 0xC8) << 24) | 0x101013);
        g.fill(x, y, x + 2, y + H, (alpha << 24) | 0xFA2D48);

        Identifier art = ArtworkCache.get(s.item().artworkUrl());
        int ax = x + 6, ay = y + 4;
        if (art != null) g.blit(art, ax, ay, ax + ART, ay + ART, 0f, 1f, 0f, 1f);
        else g.fill(ax, ay, ax + ART, ay + ART, 0xFF2A2A30);

        int tx = ax + ART + 6, tw = x + W - 6 - tx;
        int white = (alpha << 24) | 0xFFFFFF, muted = (alpha << 24) | 0x9A9AA3;
        if (!s.isPlaying()) Ui.icon(g, "pause", x + W - 6 - Ui.ICON, y + 5, muted);
        g.text(font, Ui.ellipsize(font, s.item().name(), tw - (s.isPlaying() ? 0 : 12)), tx, y + 6, white, false);
        g.text(font, Ui.ellipsize(font, s.item().artist(), tw), tx, y + 17, muted, false);

        Long dur = s.item().durationMs();
        if (dur != null && dur > 0) {
            int bx0 = tx, bx1 = x + W - 6, by = y + H - 5;
            g.fill(bx0, by, bx1, by + 2, (alpha << 24) | 0x3A3A42);
            g.fill(bx0, by, bx0 + (int) ((bx1 - bx0) * Math.min(1.0, Ui.position(s) / (double) dur)), by + 2, white);
        }
    }
}
