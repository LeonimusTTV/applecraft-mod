<p align="center"><img src="common/src/main/resources/applecraft.png" width="96" alt="AppleCraft logo" /></p>

# AppleCraft

Listen to **Apple Music** inside Minecraft: search, browse your playlists, and control playback without leaving the game.

Minecraft **26.3** · **Fabric** and **NeoForge** · Java **25** · based on the [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template).

> An Apple Music subscription is required for full tracks. Without signing in, search works but playback is limited to 30-second previews.

## How it works

Minecraft can't play Apple Music's protected streams itself, so the mod drives a small web player:

```
Minecraft (mod) ──SSE / POST──▶ http://localhost:7531 (served by the mod) ──▶ browser tab with MusicKit JS
                                                                                     │
                                         Cloudflare Worker (applecraft-api) ◀────────┘  signs the developer token
```

1. On launch, the mod starts a tiny local HTTP server (JDK `com.sun.net.httpserver`, no extra dependency).
2. It opens the player page in your default browser. **Keep that tab open**: it's the one playing the music.
3. The page gets a short-lived developer token from the AppleCraft API Worker (`applecraft-api`), then uses MusicKit JS.
4. The mod sends commands (search, play, pause…) to the page and receives the playback state back. See [PROTOCOL.md](PROTOCOL.md).

```
common/src/main/java/.../bridge/PlayerBridge.java    local server + typed Java API (search, playItem, …)
common/src/main/java/.../bridge/BridgeConfig.java    config/applecraft.json
common/src/main/java/.../client/AppleCraftScreen.java  main screen (immediate-mode rendering)
common/src/main/java/.../client/NowPlayingHud.java   "now playing" HUD card
common/src/main/java/.../client/ArtworkCache.java    artwork → Minecraft textures
common/src/main/resources/applecraft-web/            player page (bundled in the jar)
fabric/, neoforge/                                   ~20 lines each: keys, HUD layer, tick event
```

## Using it

| Key | Action |
|---|---|
| N | Open / close the AppleCraft screen |
| K | Play / pause |
| → / ← | Next / previous track |

All keys can be changed in Options → Controls → AppleCraft. Inside the screen: Space = play/pause, Backspace = back, mouse wheel = scroll.

**First play:** browsers (and Apple Music) block sound until you start playback yourself. The first time, click the **▶ button in the AppleCraft tab** (its title blinks). It stays unlocked as long as the tab is open, even across game restarts.

**Settings** (gear icon in the screen): toggle the "now playing" card and choose its corner, toggle the track-change message, and choose whether the player opens on launch.

## Configuration

Created on first launch in `config/applecraft.json`:

```json
{
  "port": 7531,
  "token": "…generated…",
  "openBrowserOnStart": true,
  "showNowPlaying": true,
  "nowPlayingPosition": "TOP_LEFT",
  "showTrackChangeMessage": true
}
```

- `workerUrl` is optional: when unset, the mod uses the Worker URL baked into the jar at build time (`http://localhost:8787` for dev builds, the deployed Worker for releases, see [Releasing](#releasing)).
- `token` is a random secret included in the page URL, so other websites can't control your player. An already open tab reconnects by itself with it.
- If port 7531 is taken, the mod tries 7532 and 7533 (these are allowed in the Worker's `ALLOWED_ORIGINS`).

## Development

Requirements: **JDK 25** ([Eclipse Temurin](https://adoptium.net), `brew install --cask temurin@25`). NeoForge's tooling also needs a **JDK 21** (`brew install --cask temurin@21`); Gradle picks both up automatically.

```bash
# in applecraft-api
npm run dev                      # local Worker on http://localhost:8787

# in this folder
./gradlew :fabric:runClient      # or :neoforge:runClient
./gradlew build                  # → fabric/build/libs and neoforge/build/libs
```

Debug logs (`→`/`←` for every command sent to the page, clicks, view loads) are at DEBUG level.

### Things worth knowing about Minecraft 26.x

- Minecraft is no longer obfuscated and several APIs were renamed: `GuiGraphics` → `GuiGraphicsExtractor`, `render()` → `extractRenderState()`, `ResourceLocation` → `Identifier`.
- **The left mouse button is now `1`** (`InputConstants.MOUSE_BUTTON_LEFT`), not `0`. Never hard-code it.
- On NeoForge, client mod constructors run **before** `Minecraft.getInstance()` exists: get the config folder from the loader (`FMLPaths.CONFIGDIR`), not from Minecraft.

## Releasing

Releases are built and uploaded to **Modrinth** and **CurseForge** by GitHub Actions ([`.github/workflows/publish.yml`](.github/workflows/publish.yml)) using the [Mod Publish Plugin](https://modmuss50.github.io/mod-publish-plugin/).

**One-time setup**

1. Deploy the Worker (`applecraft-api`) and note its URL.
2. Create the projects on Modrinth and CurseForge, then put their IDs in `gradle.properties` (`modrinth_id`, `curseforge_id`).
3. Create the tokens:
   - Modrinth (Settings → PATs): *Create versions*, *Read versions*, *Write versions*, *Read projects*, *Write projects* (the last two keep the project description in sync with `MODRINTH.md`).
   - CurseForge: Account → API tokens.
4. In the GitHub repo (Settings → Secrets and variables → Actions): secrets `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN`, variable `WORKER_URL`.

**Publishing a version**

1. Bump `version` in `gradle.properties` (optional: the release tag wins anyway).
2. Create a GitHub release with a tag like `v0.2.0` and write the changelog in its description. Tick *pre-release* to publish it as a beta.
3. The workflow builds both jars, uploads them to Modrinth and CurseForge with that changelog, updates the Modrinth description, and attaches the jars to the GitHub release.

To test the whole pipeline without uploading anything: *Actions → Publish → Run workflow* (dry run is on by default), or locally `DRY_RUN=true ./gradlew build publishMods`.

The jar gets the Worker URL at build time (`-Pworker_url`, stored in `applecraft-build.json`); publishing refuses anything that isn't `https://`, so a jar pointing to `localhost` can't be released by mistake. Players can still override it with `workerUrl` in `config/applecraft.json`.

`MODRINTH.md` is the player-facing description: it's pushed to Modrinth automatically; for CurseForge, paste it once in the project description (CurseForge has no API for that).

## License

[PolyForm Noncommercial 1.0.0](LICENSE). In short:

- ✅ Use it, modify it and share it for any **noncommercial** purpose.
- ✅ **Modpacks and videos are explicitly allowed**, including monetized ones (YouTube, Twitch, modpack platforms with creator rewards like CurseForge or Modrinth). Just link back to the mod's page.
- ❌ No commercial use (selling it, putting it behind a paywall, using it in a paid product or service…) **without asking first**. Open an issue or contact [LeonimusTTV](https://github.com/LeonimusTTV) and we'll talk about it.
- When you share it (modified or not), keep the `LICENSE` file, including its `Required Notice:` line.

This summary is not legal advice; the [LICENSE](LICENSE) text is what applies.
