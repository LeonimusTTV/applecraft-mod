# Mod ↔ player protocol

The mod runs a local HTTP server (default `http://localhost:7531`). The player page it serves talks back to it:

| Direction | Transport |
|---|---|
| mod → page | Server-Sent Events: `GET /events?t=TOKEN` |
| page → mod | `POST /reply?t=TOKEN` with a JSON body |

`TOKEN` is the random secret from `config/applecraft.json` (it's in the page URL). Wrong or missing token → `403`. Only one page is active at a time: when a new tab connects, the previous one receives `{ "type": "replaced" }`.

The page also adds `&load=ID&nav=TYPE` to `/events` for diagnostics only: `ID` is random per page load and `TYPE` is the navigation type (`navigate`, `reload`, `back_forward`). The mod logs them, so a reconnection of the same page (same `ID`) can be told apart from a real reload. The stream starts with `retry: 1000`, so a tab left open reconnects 1 s after the game restarts; the mod only opens the browser if no tab has connected 8 s after starting.

Other endpoints: `GET /` (player page), `GET /config.json` → `{ "workerUrl": "…" }`, plus the page's static files.

## Commands (mod → page)

```json
{ "type": "cmd", "id": "mc-42", "action": "search", "params": { "term": "daft punk", "limit": 8 } }
```

The page answers with the same `id`:

```json
{ "type": "result", "id": "mc-42", "ok": true,  "data": { … } }
{ "type": "result", "id": "mc-42", "ok": false, "error": "not_authorized" }
```

| action | params | data |
|---|---|---|
| `getState` | – | `State` |
| `play` / `pause` / `toggle` / `next` / `prev` | – | `null` (`prev` restarts the track if more than 3 s in) |
| `seek` | `{ positionMs }` | `null` |
| `volume` | `{ value }` (0 to 1) | `null` |
| `shuffle` | `{ on }` | `null` |
| `repeat` | `{ mode: "none" \| "one" \| "all" }` | `null` |
| `authorize` | – | `State` (opens Apple's sign-in popup in the page) |
| `playItem` | `{ kind: "song" \| "album" \| "playlist", id, startIndex? }` | `null` |
| `search` | `{ term, types?, limit? }` | `{ songs: Item[], albums: Item[], artists: Item[], playlists: Item[] }` |
| `artistTopSongs` | `{ id, limit? }` | `Item[]` |
| `artistAlbums` | `{ id, limit? }` | `Item[]` |
| `tracks` | `{ kind: "album" \| "playlist", id }` | `Item[]` (ids starting with `p.` / `l.` are library items) |
| `libraryPlaylists` | – | `Item[]` (requires sign-in) |
| `recentlyPlayed` | – | `Item[]` (requires sign-in, max 10) |

Errors: `player_not_connected` (no page connected), `server_not_running`, `timeout` (no answer within 20 s), `not_authorized`, `needs_user_gesture` (sound blocked until the user clicks the play button in the tab), `unknown_action:…`, or MusicKit's own error message.

## Pushed by the page (page → mod)

```json
{ "type": "state", "data": State }
```

Sent on every change (playback, track, volume, sound unlocked…) and every second while playing.

## Types

```ts
Item  = { kind: "song" | "album" | "artist" | "playlist", id, name, subtitle,
          artworkUrl: string | null, durationMs: number | null, playable: boolean }

State = { authorized, storefront, isPlaying, volume, shuffle, repeat, positionMs,
          audioUnlocked,   // false until playback has started once in the tab (autoplay policy)
          item: { id, name, artist, album, artworkUrl, durationMs } | null }
```

Artwork URLs are 300×300 **PNG** (Minecraft can't decode JPEG; Apple's CDN serves PNG when the extension is changed). Replace `300x300` in the URL for another size.
