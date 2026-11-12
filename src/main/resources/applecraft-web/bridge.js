// Page ↔ mod bridge (the server is embedded in the mod).
//  mod → page: Server-Sent Events on /events?t=TOKEN
//  page → mod: POST /reply?t=TOKEN
// Commands and replies follow PROTOCOL.md.

const ART_SIZE = 300;
// Minecraft can only decode PNG: Apple's CDN serves it if we change the extension.
const artUrl = (artwork, size = ART_SIZE) =>
  artwork?.url ? artwork.url.replace("{w}", size).replace("{h}", size).replace(/\.(jpg|jpeg|webp)$/i, ".png") : null;

function toItem(r) {
  const a = r.attributes ?? {};
  const kind = r.type.replace("library-", "").replace(/s$/, "");
  return {
    kind,
    id: r.id,
    name: a.name ?? "?",
    subtitle: a.artistName ?? a.curatorName ?? "", // empty → the mod shows the translated item kind
    artworkUrl: artUrl(a.artwork),
    durationMs: a.durationInMillis ?? null,
    playable: kind !== "artist",
  };
}

function snapshot(music) {
  const it = music.nowPlayingItem;
  return {
    authorized: music.isAuthorized,
    storefront: music.storefrontId,
    isPlaying: music.isPlaying,
    volume: music.volume,
    shuffle: music.shuffleMode === 1,
    repeat: ["none", "one", "all"][music.repeatMode] ?? "none",
    positionMs: Math.round((music.currentPlaybackTime ?? 0) * 1000),
    audioUnlocked: window.audioUnlocked?.() ?? true,
    item: it ? {
      id: it.id,
      name: it.attributes?.name ?? it.title,
      artist: it.attributes?.artistName ?? "",
      album: it.attributes?.albumName ?? "",
      artworkUrl: artUrl(it.attributes?.artwork),
      durationMs: it.attributes?.durationInMillis ?? Math.round((music.currentPlaybackDuration ?? 0) * 1000),
    } : null,
  };
}

const sf = "/v1/catalog/{{storefrontId}}";

function handlers(music, ui) {
  const api = async (path, params) => (await music.api.music(path, params)).data;
  const needAuth = () => { if (!music.isAuthorized) throw new Error("not_authorized"); };

  // Autoplay policy: MusicKit refuses to play until its play() has been called inside a real click.
  // In that case we queue the track and the page shows a "Play « title »" button (see index.html).
  const title = () => music.nowPlayingItem?.attributes?.name;
  const blocked = () => { ui.needGesture(title()); return new Error("needs_user_gesture"); };

  // Some playback attempts fail silently: check that it really started.
  const waitPlaying = (ms = 1500) => new Promise((resolve) => {
    if (music.isPlaying) return resolve(true);
    const t0 = Date.now();
    const iv = setInterval(() => {
      if (music.isPlaying) { clearInterval(iv); resolve(true); }
      else if (Date.now() - t0 > ms) { clearInterval(iv); resolve(false); }
    }, 100);
  });

  const playNow = async () => {
    if (!ui.canTryPlay()) throw blocked();
    try { await music.play(); }
    catch (e) { if (ui.isAutoplayError(e)) throw blocked(); throw e; }
    if (!ui.audioUnlocked() && !(await waitPlaying())) throw blocked();
  };

  return {
    getState: () => snapshot(music),
    play: playNow,
    pause: () => music.pause(),
    toggle: () => (music.isPlaying ? music.pause() : playNow()),
    next: () => music.skipToNextItem(),
    prev: () => (music.currentPlaybackTime > 3 ? music.seekToTime(0) : music.skipToPreviousItem()),
    seek: ({ positionMs }) => music.seekToTime(positionMs / 1000),
    volume: ({ value }) => { music.volume = Math.min(1, Math.max(0, value)); },
    shuffle: ({ on }) => { music.shuffleMode = on ? 1 : 0; },
    repeat: ({ mode }) => { music.repeatMode = { none: 0, one: 1, all: 2 }[mode] ?? 0; },
    authorize: () => music.authorize().then(() => snapshot(music)),

    playItem: async ({ kind, id, startIndex }) => {
      const tryNow = ui.canTryPlay();
      try {
        await music.setQueue({
          [kind]: id, startPlaying: tryNow,
          ...(startIndex != null ? { startWith: startIndex, startPosition: startIndex } : {}),
        });
      } catch (e) {
        if (ui.isAutoplayError(e)) throw blocked();
        throw e;
      }
      if (!tryNow) throw blocked();
      if (!ui.audioUnlocked() && !(await waitPlaying())) throw blocked();
    },

    search: async ({ term, types = ["songs", "albums", "artists", "playlists"], limit = 10 }) => {
      const d = await api(`${sf}/search`, { term, types, limit });
      return Object.fromEntries(types.map((t) => [t, (d.results[t]?.data ?? []).map(toItem)]));
    },
    artistTopSongs: async ({ id, limit = 20 }) => (await api(`${sf}/artists/${id}/view/top-songs`, { limit })).data.map(toItem),
    artistAlbums: async ({ id, limit = 25 }) => (await api(`${sf}/artists/${id}/albums`, { limit })).data.map(toItem),
    tracks: async ({ kind, id }) => {
      const lib = /^[pl]\./.test(id);
      if (lib) needAuth();
      const base = lib ? `/v1/me/library/${kind}s/${id}` : `${sf}/${kind}s/${id}`;
      return (await api(`${base}/tracks`, { limit: 100 })).data.map(toItem);
    },
    libraryPlaylists: async () => { needAuth(); return (await api("/v1/me/library/playlists", { limit: 100 })).data.map(toItem); },
    recentlyPlayed: async () => { needAuth(); return (await api("/v1/me/recent/played", { limit: 10 })).data.map(toItem); },
  };
}

window.startBridge = function startBridge(music, token, ui) {
  const h = handlers(music, ui);
  // No keepalive: browsers cap those requests at 64 KB in total, a large reply would be dropped.
  const post = (m) => fetch(`/reply?t=${encodeURIComponent(token)}`, { method: "POST", body: JSON.stringify(m) })
    .catch((e) => console.warn("[AppleCraft] reply not sent", m.type, m.id ?? "", e));
  const pushState = () => post({ type: "state", data: snapshot(music) });

  for (const ev of ["playbackStateDidChange", "nowPlayingItemDidChange", "authorizationStatusDidChange",
                    "playbackVolumeDidChange", "shuffleModeDidChange", "repeatModeDidChange"]) {
    music.addEventListener(ev, pushState);
  }
  setInterval(() => music.isPlaying && pushState(), 1000);
  ui.onUnlock(pushState); // tell the mod as soon as sound is unlocked

  const loadId = Math.random().toString(36).slice(2, 10);
  const nav = performance.getEntriesByType?.("navigation")?.[0]?.type ?? "unknown"; // navigate | reload | back_forward
  console.log("[AppleCraft] page load", loadId, nav);
  const es = new EventSource(`/events?t=${encodeURIComponent(token)}&load=${loadId}&nav=${encodeURIComponent(nav)}`);
  es.onopen = () => { ui.connected(true); pushState(); };
  es.onerror = () => ui.connected(false); // EventSource reconnects by itself
  es.onmessage = async (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === "replaced") { es.close(); ui.replaced(); return; }
    if (msg.type !== "cmd") return;
    const fn = h[msg.action];
    console.log("[AppleCraft] command", msg.id, msg.action, msg.params ?? {});
    try {
      if (!fn) throw new Error(`unknown_action:${msg.action}`);
      const data = (await fn(msg.params ?? {})) ?? null;
      post({ type: "result", id: msg.id, ok: true, data });
    } catch (err) {
      console.warn("[AppleCraft] command failed", msg.action, err);
      post({ type: "result", id: msg.id, ok: false, error: err?.message ?? String(err) });
    }
    pushState();
  };
};
