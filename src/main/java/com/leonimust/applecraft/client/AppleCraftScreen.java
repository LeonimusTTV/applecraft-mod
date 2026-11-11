package com.leonimust.applecraft.client;

import com.leonimust.applecraft.bridge.Models;
import com.leonimust.applecraft.bridge.Models.Item;
import com.leonimust.applecraft.bridge.PlayerBridge;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public class AppleCraftScreen extends Screen {

    private static final int BG = 0xF00F0F11;
    private static final int SIDEBAR = 0xFF151518;
    private static final int BAR = 0xFF121215;
    private static final int LINE = 0xFF26262B;
    private static final int HOVER = 0x22FFFFFF;
    private static final int PLACEHOLDER = 0xFF2A2A30;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF9A9AA3;
    private static final int ACCENT = 0xFFFA2D48;
    private static final int OK = 0xFF3DDC84;

    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(com.leonimust.applecraft.Constants.MOD_ID, "textures/gui/logo.png");

    private static final int SIDEBAR_W = 112;
    private static final int BAR_H = 46;
    private static final int PAD = 12;
    private static final int CARD = 68;      // artwork size in grids
    private static final int CARD_GAP = 10;
    private static final int ROW_H = 22;

    private enum Tab { HOME, SEARCH, LIBRARY, SETTINGS }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    // Survives closing the screen: it reopens where you left it.
    private static Tab lastTab = Tab.HOME;
    private static String lastQuery = "";

    private final PlayerBridge bridge = AppleCraftClient.bridge();
    private final List<Hit> hits = new ArrayList<>();
    private final Deque<View> history = new ArrayDeque<>();
    private View view;
    private EditBox searchBox;

    private double scroll;
    private int contentHeight;
    private String toast;
    private long toastUntil;
    private long lastTyped;
    private String pendingQuery;
    private boolean draggingVolume;
    private double dragVolume = -1;
    private long lastVolumeSend;
    private double clickX; // x of the last click (for the progress bar)
    private static double unmuteVolume = 0.5;

    public AppleCraftScreen() {
        super(Component.translatable("applecraft.ui.title"));
    }

    @Override
    protected void init() {
        int cx = SIDEBAR_W + PAD;
        searchBox = new EditBox(font, cx, PAD + 16, Math.min(320, width - cx - PAD), 18, Component.translatable("applecraft.ui.search"));
        searchBox.setHint(Component.translatable("applecraft.ui.search_hint"));
        searchBox.setMaxLength(100);
        searchBox.setValue(lastQuery);
        searchBox.setResponder(q -> { pendingQuery = q; lastTyped = System.currentTimeMillis(); });
        addRenderableWidget(searchBox);

        if (view == null) openTab(lastTab);
        else searchBox.visible = view.tab == Tab.SEARCH && history.isEmpty();
    }

    @Override
    public void tick() {
        // Search as you type, 350 ms after the last keystroke
        if (pendingQuery != null && System.currentTimeMillis() - lastTyped > 350) {
            runSearch(pendingQuery);
            pendingQuery = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void openTab(Tab tab) {
        lastTab = tab;
        history.clear();
        setView(switch (tab) {
            case HOME -> homeView();
            case SEARCH -> searchView();
            case LIBRARY -> libraryView();
            case SETTINGS -> settingsView();
        });
    }

    private void push(View v) {
        if (view != null) history.push(view);
        setView(v);
    }

    private void back() {
        if (!history.isEmpty()) setView(history.pop());
    }

    private void setView(View v) {
        com.leonimust.applecraft.Constants.LOG.debug("view → {} (tab {})", v.title.getString(), v.tab);
        view = v;
        scroll = 0;
        if (searchBox != null) {
            searchBox.visible = v.tab == Tab.SEARCH && history.isEmpty();
            searchBox.setFocused(searchBox.visible);
            if (searchBox.visible) setFocused(searchBox);
        }
    }

    private void openItem(Item item) {
        switch (item.kind()) {
            case "song" -> act(bridge.playItem(item));
            case "artist" -> push(artistView(item));
            default -> push(collectionView(item)); // album, playlist
        }
    }

    /** A page: a title, a tab, and content blocks loaded asynchronously. */
    private final class View {
        final Tab tab;
        final Component title;
        final Supplier<CompletableFuture<List<Block>>> loader;
        volatile List<Block> blocks;
        volatile String error;
        volatile boolean loading;
        boolean needsReload;

        View(Tab tab, Component title, Supplier<CompletableFuture<List<Block>>> loader) {
            this.tab = tab;
            this.title = title;
            this.loader = loader;
        }

        void load() {
            if (loader == null || loading) return;
            loading = true;
            error = null;
            needsReload = false;
            com.leonimust.applecraft.Constants.LOG.debug("loading view {}", title.getString());
            CompletableFuture<List<Block>> f;
            try {
                f = loader.get();
            } catch (RuntimeException ex) {
                com.leonimust.applecraft.Constants.LOG.error("View loader failed", ex);
                error = "unknown";
                loading = false;
                return;
            }
            f.whenComplete((b, err) -> {
                if (err != null) error = errorCode(err);
                else blocks = b;
                loading = false;
                com.leonimust.applecraft.Constants.LOG.debug("view {} loaded: {}", title.getString(),
                        err != null ? "error " + error : b.size() + " blocks");
            });
        }
    }

    private View homeView() {
        return new View(Tab.HOME, Component.translatable("applecraft.ui.home"), () -> {
            Models.State s = bridge.state();
            if (s != null && !s.authorized()) return CompletableFuture.completedFuture(List.of(loginMessage()));
            var recent = bridge.recentlyPlayed();
            var playlists = bridge.libraryPlaylists();
            return recent.thenCombine(playlists, (r, p) -> {
                List<Block> out = new ArrayList<>();
                if (!r.isEmpty()) { out.add(new Section(tr("applecraft.ui.recent"))); out.add(new Grid(r)); }
                if (!p.isEmpty()) { out.add(new Section(tr("applecraft.ui.your_playlists"))); out.add(new Grid(p)); }
                if (out.isEmpty()) out.add(new Message(tr("applecraft.ui.empty_home"), null, null));
                return out;
            });
        });
    }

    private View libraryView() {
        return new View(Tab.LIBRARY, Component.translatable("applecraft.ui.library"), () ->
                bridge.libraryPlaylists().thenApply(p -> p.isEmpty()
                        ? List.<Block>of(new Message(tr("applecraft.ui.no_playlists"), null, null))
                        : List.<Block>of(new Grid(p))));
    }

    private View searchView() {
        View v = new View(Tab.SEARCH, Component.translatable("applecraft.ui.search"), null);
        v.blocks = List.of(new Message(tr("applecraft.ui.search_intro"), null, null));
        if (!lastQuery.isBlank()) pendingQuery = lastQuery;
        return v;
    }

    private void runSearch(String q) {
        lastQuery = q;
        if (view == null || view.tab != Tab.SEARCH || !history.isEmpty()) return;
        View v = view;
        if (q.isBlank()) { v.blocks = List.of(new Message(tr("applecraft.ui.search_intro"), null, null)); return; }
        v.loading = true;
        v.error = null;
        bridge.search(q.trim(), 8).whenComplete((r, err) -> {
            v.loading = false;
            if (err != null) { v.error = errorCode(err); return; }
            if (!q.equals(lastQuery)) return; // a newer search is already running
            List<Block> out = new ArrayList<>();
            if (!r.songs().isEmpty()) { out.add(new Section(tr("applecraft.ui.songs"))); out.add(new Rows(r.songs(), null)); }
            if (!r.albums().isEmpty()) { out.add(new Section(tr("applecraft.ui.albums"))); out.add(new Grid(r.albums())); }
            if (!r.artists().isEmpty()) { out.add(new Section(tr("applecraft.ui.artists"))); out.add(new Grid(r.artists())); }
            if (!r.playlists().isEmpty()) { out.add(new Section(tr("applecraft.ui.playlists"))); out.add(new Grid(r.playlists())); }
            if (out.isEmpty()) out.add(new Message(tr("applecraft.ui.no_results"), null, null));
            v.blocks = out;
        });
    }

    private View collectionView(Item item) {
        return new View(lastTab, Component.literal(item.name()), () ->
                bridge.tracks(item).thenApply(tracks -> List.of(new Header(item, tracks.size()), new Rows(tracks, item))));
    }

    private View artistView(Item artist) {
        return new View(lastTab, Component.literal(artist.name()), () ->
                bridge.artistTopSongs(artist.id()).thenCombine(bridge.artistAlbums(artist.id()), (top, albums) -> {
                    List<Block> out = new ArrayList<>();
                    out.add(new Header(artist, -1));
                    if (!top.isEmpty()) { out.add(new Section(tr("applecraft.ui.top_songs"))); out.add(new Rows(top.subList(0, Math.min(10, top.size())), null)); }
                    if (!albums.isEmpty()) { out.add(new Section(tr("applecraft.ui.albums"))); out.add(new Grid(albums)); }
                    return out;
                }));
    }

    private View settingsView() {
        View v = new View(Tab.SETTINGS, Component.translatable("applecraft.ui.settings"), null);
        var cfg = bridge.config();
        Runnable save = cfg::save;
        v.blocks = List.of(
                new Section(tr("applecraft.settings.display")),
                new Toggle(tr("applecraft.settings.now_playing"), tr("applecraft.settings.now_playing.desc"),
                        () -> cfg.showNowPlaying, b -> { cfg.showNowPlaying = b; save.run(); }),
                new Choice(tr("applecraft.settings.position"), tr("applecraft.settings.position.desc"),
                        List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"),
                        () -> cfg.nowPlayingPosition, p -> { cfg.nowPlayingPosition = p; save.run(); }),
                new Toggle(tr("applecraft.settings.track_message"), tr("applecraft.settings.track_message.desc"),
                        () -> cfg.showTrackChangeMessage, b -> { cfg.showTrackChangeMessage = b; save.run(); }),
                new Section(tr("applecraft.settings.player")),
                new Toggle(tr("applecraft.settings.open_browser"), tr("applecraft.settings.open_browser.desc"),
                        () -> cfg.openBrowserOnStart, b -> { cfg.openBrowserOnStart = b; save.run(); }),
                new Message(tr("applecraft.settings.player.desc"), tr("applecraft.ui.open_player"), bridge::openPlayerPage));
        return v;
    }

    private Message loginMessage() {
        return new Message(tr("applecraft.ui.login_needed"), tr("applecraft.ui.open_player"), bridge::openPlayerPage);
    }

    private sealed interface Block permits Section, Grid, Rows, Header, Message, Toggle, Choice {}
    private record Toggle(String label, String desc, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) implements Block {}
    private record Choice(String label, String desc, List<String> values, Supplier<String> get, java.util.function.Consumer<String> set) implements Block {}
    private record Section(String title) implements Block {}
    private record Grid(List<Item> items) implements Block {}
    /** container != null: a click plays the list starting from this track (album/playlist). */
    private record Rows(List<Item> items, Item container) implements Block {}
    private record Header(Item item, int trackCount) implements Block {}
    private record Message(String text, String button, Runnable action) implements Block {}

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hits.clear();
        Models.Status status = bridge.status();

        // Reload the view when the player comes back
        if (status.playerUp() && view.loader != null && (view.blocks == null || view.needsReload) && !view.loading) view.load();

        renderContent(g, mouseX, mouseY, status);
        renderSidebar(g, mouseX, mouseY, status);
        renderPlayerBar(g, mouseX, mouseY);

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = font.width(toast) + 16;
            int tx = SIDEBAR_W + (width - SIDEBAR_W - tw) / 2, ty = height - BAR_H - 26;
            g.fill(tx, ty, tx + tw, ty + 18, 0xEE2A1418);
            g.fill(tx, ty, tx + 2, ty + 18, ACCENT);
            g.text(font, toast, tx + 8, ty + 5, TEXT, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick); // widgets (search box)
    }

    private void renderSidebar(GuiGraphicsExtractor g, int mx, int my, Models.Status status) {
        g.fill(0, 0, SIDEBAR_W, height - BAR_H, SIDEBAR);
        g.fill(SIDEBAR_W - 1, 0, SIDEBAR_W, height - BAR_H, LINE);

        g.blit(LOGO, PAD - 2, PAD - 4, PAD + 14, PAD + 12, 0f, 1f, 0f, 1f);
        g.text(font, "Apple", PAD + 18, PAD, TEXT, false);
        g.text(font, "Craft", PAD + 18 + font.width("Apple"), PAD, ACCENT, false);

        int y = PAD + 22;
        y = navItem(g, mx, my, y, "home", tr("applecraft.ui.home"), Tab.HOME);
        y = navItem(g, mx, my, y, "search", tr("applecraft.ui.search"), Tab.SEARCH);
        y = navItem(g, mx, my, y, "library", tr("applecraft.ui.library"), Tab.LIBRARY);
        navItem(g, mx, my, y + 6, "gear", tr("applecraft.ui.settings"), Tab.SETTINGS);

        // Player status, at the bottom
        Models.State s = bridge.state();
        int by = height - BAR_H - 40;
        int dot;
        String label;
        String button = null;
        if (!status.playerUp()) { dot = ACCENT; label = tr("applecraft.ui.player_closed"); button = tr("applecraft.ui.open_player"); }
        else if (s != null && s.soundBlocked()) { dot = 0xFFE6A23C; label = tr("applecraft.ui.sound_blocked"); }
        else if (s != null && !s.authorized()) { dot = 0xFFE6A23C; label = tr("applecraft.ui.not_logged_in"); button = tr("applecraft.ui.login"); }
        else { dot = OK; label = tr("applecraft.ui.connected"); }

        g.fill(PAD, by + 3, PAD + 5, by + 8, dot);
        if (s != null && s.soundBlocked() && status.playerUp()) {
            g.text(font, ellipsize(label, SIDEBAR_W - PAD * 2 - 9), PAD + 9, by + 1, MUTED, false);
            g.text(font, ellipsize(tr("applecraft.ui.sound_blocked_hint"), SIDEBAR_W - PAD * 2), PAD, by + 14, 0xFF6E6E78, false);
            g.text(font, ellipsize(tr("applecraft.ui.sound_blocked_hint2"), SIDEBAR_W - PAD * 2), PAD, by + 24, 0xFF6E6E78, false);
            return;
        }
        g.text(font, ellipsize(label, SIDEBAR_W - PAD * 2 - 9), PAD + 9, by + 1, MUTED, false);
        if (button != null) pill(g, mx, my, PAD, by + 14, SIDEBAR_W - PAD * 2, button, bridge::openPlayerPage);
    }

    private int navItem(GuiGraphicsExtractor g, int mx, int my, int y, String icon, String label, Tab tab) {
        boolean active = view != null && view.tab == tab;
        int x0 = 6, x1 = SIDEBAR_W - 6, h = 18;
        boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + h;
        if (active) g.fill(x0, y, x1, y + h, 0x33FA2D48);
        else if (hover) g.fill(x0, y, x1, y + h, HOVER);
        Ui.icon(g, icon, x0 + 6, y + 4, active ? ACCENT : MUTED);
        g.text(font, label, x0 + 20, y + 5, active ? TEXT : MUTED, false);
        hits.add(new Hit(x0, y, x1 - x0, h, () -> openTab(tab)));
        return y + h + 2;
    }

    private void renderContent(GuiGraphicsExtractor g, int mx, int my, Models.Status status) {
        int x0 = SIDEBAR_W + PAD, x1 = width - PAD;
        int top = PAD;

        // Header: back button + title
        int tx = x0;
        if (!history.isEmpty()) {
            pill(g, mx, my, x0, top - 2, 18, "←", this::back);
            tx += 24;
        }
        g.text(font, view.title, tx, top + 2, TEXT, false);
        if (view.loading) g.text(font, tr("applecraft.ui.loading"), x1 - font.width(tr("applecraft.ui.loading")), top + 2, MUTED, false);

        int viewTop = top + 16 + (searchBox != null && searchBox.visible ? 26 : 4);
        int viewBottom = height - BAR_H - 4;
        int viewH = viewBottom - viewTop;
        int w = x1 - x0;

        int firstContentHit = hits.size();
        g.enableScissor(x0 - 4, viewTop, x1 + 4, viewBottom);
        int y = viewTop - (int) scroll;
        boolean mouseIn = my >= viewTop && my < viewBottom;
        int hm = mouseIn ? my : Integer.MIN_VALUE; // no hover outside the visible area

        if (!status.playerUp() && view.loader != null) {
            view.needsReload = true;
            y += renderMessage(g, mx, hm, x0, y, w, new Message(tr("applecraft.ui.player_closed_long"), tr("applecraft.ui.open_player"), bridge::openPlayerPage));
        } else if (view.error != null) {
            y += renderError(g, mx, hm, x0, y, w, view.error);
        } else if (view.blocks != null) {
            for (Block b : view.blocks) y += renderBlock(g, mx, hm, x0, y, w, b) + 8;
        } else if (view.loading) {
            g.text(font, tr("applecraft.ui.loading"), x0, y + 6, MUTED, false);
            y += 20;
        }
        g.disableScissor();

        contentHeight = y + (int) scroll - viewTop;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewH)));

        // Content click areas: clip them to the visible part
        for (int i = hits.size() - 1; i >= firstContentHit; i--) {
            Hit h = hits.get(i);
            int hy0 = Math.max(h.y, viewTop), hy1 = Math.min(h.y + h.h, viewBottom);
            if (hy1 <= hy0) hits.remove(i);
            else hits.set(i, new Hit(h.x, hy0, h.w, hy1 - hy0, h.action));
        }

        // Scrollbar
        if (contentHeight > viewH) {
            int barH = Math.max(20, viewH * viewH / contentHeight);
            int barY = viewTop + (int) ((viewH - barH) * (scroll / (contentHeight - viewH)));
            g.fill(x1 + 6, barY, x1 + 8, barY + barH, 0x55FFFFFF);
        }
    }

    private int renderBlock(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Block b) {
        return switch (b) {
            case Section s -> { g.text(font, s.title(), x, y + 4, TEXT, false); g.fill(x, y + 15, x + w, y + 16, LINE); yield 20; }
            case Grid gr -> renderGrid(g, mx, my, x, y, w, gr.items());
            case Rows r -> renderRows(g, mx, my, x, y, w, r);
            case Header h -> renderHeader(g, mx, my, x, y, w, h);
            case Message m -> renderMessage(g, mx, my, x, y, w, m);
            case Toggle t -> renderToggle(g, mx, my, x, y, w, t);
            case Choice c -> renderChoice(g, mx, my, x, y, w, c);
        };
    }

    private int settingRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, String desc, int controlW, Runnable onClick) {
        int h = 30;
        boolean hover = mx >= x - 4 && mx < x + w + 4 && my >= y && my < y + h;
        if (hover) g.fill(x - 4, y, x + w + 4, y + h, 0x11FFFFFF);
        g.text(font, label, x, y + 6, TEXT, false);
        g.text(font, ellipsize(desc, w - controlW - 16), x, y + 17, MUTED, false);
        hits.add(new Hit(x - 4, y, w + 8, h, onClick));
        return h;
    }

    private int renderToggle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Toggle t) {
        boolean on = t.get().getAsBoolean();
        int h = settingRow(g, mx, my, x, y, w, t.label(), t.desc(), 30, () -> t.set().accept(!on));
        // switch
        int sx1 = x + w, sx0 = sx1 - 24, sy = y + 9;
        g.fill(sx0, sy, sx1, sy + 12, on ? ACCENT : 0xFF3A3A42);
        int knob = on ? sx1 - 11 : sx0 + 1;
        g.fill(knob, sy + 1, knob + 10, sy + 11, TEXT);
        return h;
    }

    private int renderChoice(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Choice c) {
        String cur = c.get().get();
        int i = Math.max(0, c.values().indexOf(cur));
        String label = tr("applecraft.settings.position." + cur.toLowerCase());
        int cw = font.width(label) + 28;
        int h = settingRow(g, mx, my, x, y, w, c.label(), c.desc(), cw, () -> c.set().accept(c.values().get((i + 1) % c.values().size())));
        int bx1 = x + w, bx0 = bx1 - cw, by = y + 7;
        g.fill(bx0, by, bx1, by + 16, 0xFF26262C);
        g.text(font, "‹", bx0 + 5, by + 4, MUTED, false);
        g.text(font, "›", bx1 - 9, by + 4, MUTED, false);
        g.text(font, label, bx0 + (cw - font.width(label)) / 2, by + 4, TEXT, false);
        return h;
    }

    private int renderGrid(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, List<Item> items) {
        int cols = Math.max(1, (w + CARD_GAP) / (CARD + CARD_GAP));
        int cardH = CARD + 26;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int cx = x + (i % cols) * (CARD + CARD_GAP);
            int cy = y + (i / cols) * (cardH + CARD_GAP);
            boolean hover = mx >= cx && mx < cx + CARD && my >= cy && my < cy + cardH;
            if (hover) g.fill(cx - 3, cy - 3, cx + CARD + 3, cy + cardH, HOVER);
            artwork(g, it.artworkUrl(), cx, cy, CARD);
            if (hover && it.playable()) {
                // small ▶ button on the artwork
                g.fill(cx + CARD - 18, cy + CARD - 18, cx + CARD - 3, cy + CARD - 3, ACCENT);
                g.text(font, "▶", cx + CARD - 14, cy + CARD - 14, TEXT, false);
                hits.add(new Hit(cx + CARD - 18, cy + CARD - 18, 15, 15, () -> act(bridge.playItem(it))));
            }
            g.text(font, ellipsize(it.name(), CARD), cx, cy + CARD + 4, TEXT, false);
            g.text(font, ellipsize(kindLabel(it), CARD), cx, cy + CARD + 14, MUTED, false);
            hits.add(new Hit(cx, cy, CARD, cardH, () -> openItem(it)));
        }
        int rows = (items.size() + cols - 1) / cols;
        return rows * (cardH + CARD_GAP);
    }

    private int renderRows(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Rows r) {
        Models.State s = bridge.state();
        String playingId = s != null && s.item() != null ? s.item().id() : null;
        for (int i = 0; i < r.items().size(); i++) {
            Item it = r.items().get(i);
            int ry = y + i * ROW_H;
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + ROW_H;
            boolean playing = it.id().equals(playingId);
            if (hover) g.fill(x - 4, ry, x + w + 4, ry + ROW_H, HOVER);

            int tx = x;
            if (r.container() != null) {
                String n = playing ? "♪" : hover ? "▶" : String.valueOf(i + 1);
                g.text(font, n, x + 12 - font.width(n) / 2, ry + 7, playing ? ACCENT : MUTED, false);
                tx += 26;
            } else {
                artwork(g, it.artworkUrl(), x, ry + 2, 18);
                tx += 26;
            }
            String dur = fmt(it.durationMs());
            int durW = font.width(dur);
            int textW = w - (tx - x) - durW - 12;
            int nameW = Math.min(font.width(it.name()), textW * 3 / 5);
            g.text(font, ellipsize(it.name(), nameW + 1), tx, ry + 7, playing ? ACCENT : TEXT, false);
            if (it.subtitle() != null && !it.subtitle().isEmpty())
                g.text(font, ellipsize(it.subtitle(), textW - nameW - 10), tx + nameW + 10, ry + 7, MUTED, false);
            g.text(font, dur, x + w - durW, ry + 7, MUTED, false);

            final int index = i;
            hits.add(new Hit(x - 4, ry, w + 8, ROW_H, () ->
                    act(r.container() != null ? bridge.playInList(r.container(), index) : bridge.playItem(it))));
        }
        return r.items().size() * ROW_H;
    }

    private int renderHeader(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Header h) {
        Item it = h.item();
        int size = 72;
        artwork(g, it.artworkUrl(), x, y, size);
        int tx = x + size + 14;
        g.text(font, kindLabel(it).toUpperCase(), tx, y + 4, MUTED, false);
        g.text(font, ellipsize(it.name(), w - size - 14), tx, y + 18, TEXT, false);
        String sub = it.subtitle() != null ? it.subtitle() : "";
        if (h.trackCount() >= 0) sub += (sub.isEmpty() ? "" : " · ") + Component.translatable("applecraft.ui.tracks", h.trackCount()).getString();
        g.text(font, ellipsize(sub, w - size - 14), tx, y + 30, MUTED, false);
        if (it.playable()) {
            int bx = tx;
            bx += pill(g, mx, my, bx, y + 48, -1, "▶  " + tr("applecraft.ui.play"), () -> act(bridge.playItem(it)), true) + 6;
            pill(g, mx, my, bx, y + 48, -1, "⇄  " + tr("applecraft.ui.shuffle"), () ->
                    act(bridge.shuffle(true).thenCompose(v -> bridge.playItem(it))), false);
        }
        return size + 6;
    }

    private int renderMessage(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, Message m) {
        Component text = Component.literal(m.text());
        int wrap = Math.min(w, 320);
        g.textWithWordWrap(font, text, x, y + 6, wrap, MUTED, false); // returns void before 26.3, so measure ourselves
        int h = 6 + font.split(text, wrap).size() * font.lineHeight;
        if (m.button() != null) {
            pill(g, mx, my, x, y + h + 8, -1, m.button(), m.action(), true);
            h += 30;
        }
        return h + 6;
    }

    private int renderError(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String code) {
        return switch (code) {
            case "not_authorized" -> renderMessage(g, mx, my, x, y, w, loginMessage());
            case PlayerBridge.ERR_PLAYER_NOT_CONNECTED, PlayerBridge.ERR_SERVER_NOT_RUNNING ->
                    renderMessage(g, mx, my, x, y, w, new Message(tr("applecraft.ui.player_closed_long"), tr("applecraft.ui.open_player"), bridge::openPlayerPage));
            default -> renderMessage(g, mx, my, x, y, w, new Message(errorText(code), tr("applecraft.ui.retry"), view::load));
        };
    }

    private void renderPlayerBar(GuiGraphicsExtractor g, int mx, int my) {
        int top = height - BAR_H;
        g.fill(0, top, width, height, BAR);
        g.fill(0, top, width, top + 1, LINE);

        Models.State s = bridge.state();
        Models.NowPlaying np = s != null ? s.item() : null;

        // left: artwork + title
        int left = 8, artSize = BAR_H - 16;
        artwork(g, np != null ? np.artworkUrl() : null, left, top + 8, artSize);
        int infoW = Math.max(40, width / 2 - 130 - (left + artSize + 8));
        if (np != null) {
            g.text(font, ellipsize(np.name(), infoW), left + artSize + 8, top + 12, TEXT, false);
            g.text(font, ellipsize(np.artist(), infoW), left + artSize + 8, top + 24, MUTED, false);
        } else {
            g.text(font, tr("applecraft.ui.nothing_playing"), left + artSize + 8, top + 18, MUTED, false);
        }

        // center: controls (9×9 pixel-art icons, 18×16 click areas)
        int cx = width / 2, cy = top + 13;
        boolean playing = s != null && s.isPlaying();
        boolean shuffle = s != null && s.shuffle();
        String repeat = s != null ? s.repeat() : "none";
        control(g, mx, my, cx - 60, cy, "shuffle", shuffle, () -> act(bridge.shuffle(!shuffle)));
        control(g, mx, my, cx - 32, cy, "prev", null, () -> act(bridge.prev()));
        boolean ph = mx >= cx - 11 && mx < cx + 11 && my >= cy - 10 && my < cy + 10;
        g.fill(cx - 11, cy - 10, cx + 11, cy + 10, ph ? 0xFFFF4D64 : ACCENT);
        Ui.icon(g, playing ? "pause" : "play", cx - 4, cy - 4, TEXT);
        hits.add(new Hit(cx - 11, cy - 10, 22, 20, () -> act(bridge.toggle())));
        control(g, mx, my, cx + 32, cy, "next", null, () -> act(bridge.next()));
        control(g, mx, my, cx + 60, cy, "one".equals(repeat) ? "repeat_one" : "repeat", !"none".equals(repeat), () ->
                act(bridge.repeat(switch (repeat) { case "none" -> "all"; case "all" -> "one"; default -> "none"; })));

        // progress
        long dur = np != null && np.durationMs() != null ? np.durationMs() : 0;
        long pos = Ui.position(s);
        int px0 = cx - 120, px1 = cx + 120, py = top + 33;
        String t0 = fmt(pos), t1 = fmt(dur);
        g.text(font, t0, px0 - font.width(t0) - 6, py - 3, MUTED, false);
        g.text(font, t1, px1 + 6, py - 3, MUTED, false);
        g.fill(px0, py, px1, py + 3, 0xFF3A3A42);
        if (dur > 0) {
            int fill = px0 + (int) ((px1 - px0) * Math.min(1, (double) pos / dur));
            g.fill(px0, py, fill, py + 3, my >= py - 4 && my < py + 7 && mx >= px0 && mx < px1 ? ACCENT : TEXT);
            hits.add(new Hit(px0, py - 4, px1 - px0, 11, () ->
                    act(bridge.seek((long) (Math.clamp((clickX - px0) / (px1 - px0), 0, 1) * dur)))));
        }

        // right: volume
        int vx1 = width - 14, vx0 = vx1 - 70, vy = top + 21;
        double vol = dragVolume >= 0 ? dragVolume : s != null ? s.volume() : 0.5;
        boolean muted = vol <= 0.001;
        boolean vh = mx >= vx0 - 18 && mx < vx0 - 2 && my >= vy - 7 && my < vy + 9;
        Ui.icon(g, muted ? "mute" : "volume", vx0 - 15, vy - 3, vh ? TEXT : MUTED);
        hits.add(new Hit(vx0 - 18, vy - 7, 16, 16, () -> {
            if (!muted) unmuteVolume = vol;
            act(bridge.volume(muted ? (unmuteVolume > 0 ? unmuteVolume : 0.5) : 0));
        }));
        g.fill(vx0, vy, vx1, vy + 3, 0xFF3A3A42);
        g.fill(vx0, vy, vx0 + (int) ((vx1 - vx0) * vol), vy + 3, TEXT);
        g.fill(vx0 + (int) ((vx1 - vx0) * vol) - 1, vy - 2, vx0 + (int) ((vx1 - vx0) * vol) + 2, vy + 5, TEXT);
    }

    /** Icon button: active = null (normal), true (on: pink + dot), false (off: grey). */
    private void control(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, String icon, Boolean active, Runnable action) {
        int x = cx - 9, y = cy - 8;
        boolean hover = mx >= x && mx < x + 18 && my >= y && my < y + 16;
        if (hover) g.fill(x, y, x + 18, y + 16, HOVER);
        int color = Boolean.TRUE.equals(active) ? ACCENT : Boolean.FALSE.equals(active) && !hover ? MUTED : TEXT;
        Ui.icon(g, icon, cx - 4, cy - 4, color);
        if (Boolean.TRUE.equals(active)) g.fill(cx - 1, cy + 6, cx + 1, cy + 8, ACCENT);
        hits.add(new Hit(x, y, 18, 16, action));
    }

    private void pill(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable action) {
        pill(g, mx, my, x, y, w, label, action, false);
    }

    /** Flat button. w = -1: auto width. Returns the width. */
    private int pill(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable action, boolean primary) {
        if (w < 0) w = font.width(label) + 16;
        int h = 18;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = primary ? (hover ? 0xFFFF4D64 : ACCENT) : (hover ? 0xFF34343B : 0xFF26262C);
        g.fill(x, y, x + w, y + h, bg);
        String l = ellipsize(label, w - 8);
        g.text(font, l, x + (w - font.width(l)) / 2, y + 5, TEXT, false);
        hits.add(new Hit(x, y, w, h, action));
        return w;
    }

    private void artwork(GuiGraphicsExtractor g, String url, int x, int y, int size) {
        Identifier tex = ArtworkCache.get(url);
        if (tex != null) {
            g.blit(tex, x, y, x + size, y + size, 0f, 1f, 0f, 1f);
        } else {
            g.fill(x, y, x + size, y + size, PLACEHOLDER);
            if (size >= 24) g.text(font, "♪", x + size / 2 - 3, y + size / 2 - 4, 0xFF55555E, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        Hit target = null;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(e.x(), e.y())) { target = hits.get(i); break; }
        com.leonimust.applecraft.Constants.LOG.debug("click ({}, {}) button={} screen={}x{} areas={} hit={}",
                (int) e.x(), (int) e.y(), e.button(), width, height, hits.size(), target != null ? target.x + "," + target.y + " " + target.w + "x" + target.h : "none");

        // The search box gets priority when clicked
        if (searchBox != null && searchBox.visible && searchBox.isMouseOver(e.x(), e.y())) return super.mouseClicked(e, doubleClick);
        if (searchBox != null && searchBox.isFocused()) searchBox.setFocused(false);
        // Careful: since 26.x the left button is 1 (it used to be 0) → always use the constant.
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(e, doubleClick);

        // volume: click + drag
        int top = height - BAR_H, vx1 = width - 14, vx0 = vx1 - 70, vy = top + 21;
        if (e.x() >= vx0 - 2 && e.x() <= vx1 + 2 && e.y() >= vy - 5 && e.y() <= vy + 8) {
            draggingVolume = true;
            dragVolume = Math.clamp((e.x() - vx0) / (vx1 - vx0), 0, 1);
            return true;
        }
        // Hits are added in drawing order: target = the last one drawn (on top).
        if (target != null) {
            clickX = e.x();
            playClick();
            try {
                target.action().run();
            } catch (RuntimeException ex) {
                com.leonimust.applecraft.Constants.LOG.error("Click action failed", ex);
            }
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (draggingVolume) {
            int vx1 = width - 14, vx0 = vx1 - 70;
            dragVolume = Math.clamp((e.x() - vx0) / (vx1 - vx0), 0, 1);
            if (System.currentTimeMillis() - lastVolumeSend > 80) { // at most ~12 updates per second
                lastVolumeSend = System.currentTimeMillis();
                bridge.volume(dragVolume);
            }
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (draggingVolume) {
            draggingVolume = false;
            double v = dragVolume;
            act(bridge.volume(v).whenComplete((r, err) -> dragVolume = -1));
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 24;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        boolean typing = searchBox != null && searchBox.isFocused();
        if (!typing && e.key() == InputConstants.KEY_SPACE) { act(bridge.toggle()); return true; }
        if (!typing && e.key() == InputConstants.KEY_BACKSPACE && !history.isEmpty()) { back(); return true; }
        if (typing && e.key() == InputConstants.KEY_RETURN) { runSearch(searchBox.getValue()); pendingQuery = null; return true; }
        // the key that opens the screen also closes it (except while typing)
        if (!typing && AppleCraftClient.OPEN_SCREEN != null && AppleCraftClient.OPEN_SCREEN.matches(e)) { onClose(); return true; }
        return super.keyPressed(e);
    }

    private void playClick() {
        net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
    }

    /** Runs a command; shows a toast on error. */
    private void act(CompletableFuture<?> f) {
        f.exceptionally(err -> {
            String code = errorCode(err);
            // Don't open a new tab here: it would replace the one waiting for the click.
            toast = errorText(code);
            toastUntil = System.currentTimeMillis() + ("needs_user_gesture".equals(code) ? 7000 : 4000);
            return null;
        });
    }

    static String errorCode(Throwable err) {
        Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (c instanceof PlayerBridge.BridgeException) return c.getMessage();
        if (c instanceof java.util.concurrent.TimeoutException) return "timeout";
        return "unknown";
    }

    static String errorText(String code) {
        return switch (code) {
            case PlayerBridge.ERR_SERVER_NOT_RUNNING -> tr("applecraft.error.server_not_running");
            case PlayerBridge.ERR_PLAYER_NOT_CONNECTED -> tr("applecraft.error.player_not_connected");
            case "not_authorized" -> tr("applecraft.error.not_authorized");
            case "needs_user_gesture" -> tr("applecraft.error.needs_user_gesture");
            case "timeout" -> tr("applecraft.error.timeout");
            default -> Component.translatable("applecraft.error.generic", code).getString();
        };
    }

    private String kindLabel(Item it) {
        String kind = tr("applecraft.kind." + it.kind());
        return it.subtitle() == null || it.subtitle().isEmpty() || "artist".equals(it.kind()) ? kind : it.subtitle();
    }

    private String ellipsize(String s, int maxW) {
        return Ui.ellipsize(font, s, maxW);
    }

    private static String fmt(Long ms) {
        return Ui.fmt(ms);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
