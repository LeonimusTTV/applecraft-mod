package com.leonimust.applecraft.client;

import com.leonimust.applecraft.Constants;
import com.leonimust.applecraft.bridge.Models;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Small rendering helpers shared by the screen and the HUD. */
public final class Ui {
    private Ui() {}

    public static final int ICON = 9; // icons are 9×9 pixel art

    /** White icon (textures/gui/icons/NAME.png) tinted with the given ARGB color. */
    public static void icon(GuiGraphicsExtractor g, String name, int x, int y, int color) {
        Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/icons/" + name + ".png");
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, ICON, ICON, ICON, ICON, color);
    }

    public static String ellipsize(Font font, String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width("…"))) + "…";
    }

    public static String fmt(Long ms) {
        if (ms == null || ms <= 0) return "0:00";
        long sec = ms / 1000;
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }

    // ---- playback position, interpolated between two "state" messages (sent every second)
    private static Models.State lastState;
    private static long lastStateAt;

    public static long position(Models.State s) {
        if (s == null || s.item() == null) return 0;
        if (s != lastState) { lastState = s; lastStateAt = System.currentTimeMillis(); }
        long pos = s.positionMs() + (s.isPlaying() ? System.currentTimeMillis() - lastStateAt : 0);
        Long dur = s.item().durationMs();
        return dur != null ? Math.min(pos, dur) : pos;
    }
}
