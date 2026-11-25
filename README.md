# CrunchyMe

CrunchyMe is a CloudStream 3 provider built around one idea: **metadata and playback are separate problems**.

TMDB or IMDb/OMDb provides the catalog, search, artwork and episode list. Playback is resolved by a small
"stream farm" that can run several independent sources in parallel and pick the best playable
result. The farm is deliberately pluggable: a legal public-domain archive is built in, Stremio/Nuvio
addons can be attached from settings, and new providers can be added as URL templates without
recompiling the plugin.

```
TMDB (metadata)  ──►  LinkRequest  ──►  Sources.stream()  ──►  MediaProbe  ──►  CloudStream player
                                         │
                                         ├── Internet Archive (built in, public domain)
                                         ├── Built-in servers (VidBolt, Vidrift, Vidly, …)
                                         ├── Stremio / Nuvio addons (user supplied)
                                         └── Custom providers (user supplied templates)
```

## Install

1. Nothing to edit for GitHub Actions: the workflow fills in your repository automatically.
   For local builds, set `GITHUB_REPOSITORY=owner/repo` or edit the fallback in
   `CrunchyMe/build.gradle.kts` and `repo.json`.
2. Push the repository. GitHub Actions builds on every push to `main` and publishes
   `*.cs3` + `plugins.json` to the `builds` branch. The first run creates that branch
   automatically; no manual setup is needed.
3. In CloudStream: **Settings → Extensions → Add repository**, then paste
   `https://raw.githubusercontent.com/<user>/<repo>/builds/repo.json`.

### Publish from a fresh checkout

```bash
git add .
git commit -m "Add CrunchyMe provider"
git branch -M main
git remote add origin https://github.com/<user>/<repo>.git
git push -u origin main
```

The first workflow run creates the `builds` branch and publishes the artifacts.
Each CI build uses the workflow run number as the plugin version, and the published plugin URL
includes a build tag. That prevents an update from reusing a stale cached `.cs3` file.

## First run: add a TMDB key

CrunchyMe ships without a key. Home rows and search stay empty until one is configured.

1. Create a free account on [themoviedb.org](https://www.themoviedb.org/settings/api).
2. Request an API key (Developer, free).
3. In CloudStream open **CrunchyMe → Settings** (the gear next to the provider) and paste the
   **v3 auth key** (the 32-character hex string, not the long `eyJ...` JWT).
4. Tap **Test** to verify, then **Save**.

The key is stored in the app's private `SharedPreferences` (`crunchyme_settings`). It is never
bundled into the plugin and never sent anywhere except `api.themoviedb.org`.

## Settings

| Setting | What it does |
| --- | --- |
| Catalog provider | **TMDB** (default) or **IMDb**. IMDb uses OMDb and works without a TMDB key. |
| TMDB API key | Enables the TMDB catalog, search, details and episodes. |
| Minimum quality | Drops streams whose height is *known* to be below the floor (480p / 720p / 1080p / Any). Unknown heights are kept. |
| Provider priority | Drag a provider by its handle or type a score from 1 to 100 (100 starts first). Changing priority alone does not clear cached links or reload the screen. |
| Parallel tasks | How many providers resolve at once (2–16). Higher is faster; lower is gentler on slow devices. |
| Internet Archive | Built-in legal source for public-domain films. |
| Built-in servers | Turn individual providers on or off (VidBolt, Vidrift, Vidly, Bingr, NetMirror, MovieBox, NetNaija, Toonix, …). |
| Stremio / Nuvio addons | Enables the addon source. |
| Custom providers | Enable/disable or remove user-defined templates. |
| Clear caches | Drops TMDB, link and addon-manifest caches. |
| Reset health | Re-enables sources that were temporarily circuit-broken. |

## How a play request works

1. CloudStream calls `CrunchyMeProvider.loadLinks(data, ...)`.
2. `Refs.parse` extracts the TMDB id, IMDB id, type, season and episode from the URL. The URL is
   only an identifier — no provider website is scraped for metadata.
3. `Tmdb.detail` supplies the title, year and IMDB id (cached for 15 minutes).
4. `Sources.stream` runs the enabled sources in priority order, up to the configured parallel-task
   limit. Each source has its own timeout; a failing source cannot block the others. Results are
   delivered as soon as each source finishes, so the first links appear while slower providers are
   still running.
5. `MediaProbe` inspects each candidate:
   - HLS: fetches the master playlist, reads `RESOLUTION`, `#EXT-X-MEDIA` audio languages and
     `TYPE=SUBTITLES` tracks.
   - DASH: reads `<Representation height>` and audio `lang`.
   - MP4/MKV: issues a `Range` request and parses the `moov → trak → tkhd` box for the real height.
6. Candidates below the quality floor (when the height is known) are dropped. If every
   candidate from a source is below the floor, its best low-quality candidate is kept instead of
   returning nothing. File-index HTML pages and Cloudflare challenges are rejected here, which
   prevents CloudStream's "video is unsupported" error from a download page being played as video. Links are deduplicated, emitted progressively, cached for 2 minutes, and
   sent with the right `Referer`/headers and subtitle tracks.

## Sources

### Internet Archive (built in)

Public-domain and openly licensed films with direct MP4 files. It is movie-only: archive.org
series metadata is not reliable enough to build an episode list from.

The resolver searches `advancedsearch.php`, ranks results by title and year, then reads
`archive.org/metadata/<id>` and keeps playable video files (`h.264`, `MPEG4`, `Matroska`, `WebM`,
`Ogg Video`). Trailers, samples, thumbnails and `.ia.` derivative files are filtered out.

### Built-in servers

The provider ships with twenty-six optional servers. They run in the configured priority order,
up to the parallel-task limit, and each one has its own timeout and circuit breaker. Anime-only
servers are skipped automatically for non-anime titles.

| Server | Mode | Default | Notes |
| --- | --- | --- | --- |
| VidBolt | JSON API | on | Reads `stream.sources`, keeps the inline HLS/DASH master and its audio/subtitle tracks. Falls back through the server list when the first response is empty. |
| Vidrift | Embed | on | Parses `var embedMeta`, uses the pre-warmed `orionStreams` manifests, and falls back to the token-gated source API. |
| Vidly | Embed | on | Scans the embed page for HLS masters and probes each one. |
| Bingr | Embed | on | Extracts `master.m3u8` URLs from the watch page. Cloudflare may challenge this host. |
| NetMirror | JSON API | on | Reads `streams[]` and `mp4`; uses the `videodownloader.site` referer required by the file CDN. |
| Atlantic | JSON API | off | Tries the `stream.hls.lol` provider endpoints, then the legacy CDN. Rate-limited on some networks. |
| MovieBox | JSON API | on | H5 API: bootstrap token, search, play/download. Keeps signed MP4 URLs and the `signHeaderKey`/`signCookie` headers. Falls back to the mobile API. |
| NetNaija | JSON API | on | MovieBox API with the NetNaija site/source. Use it when MovieBox is region-blocked. |
| Toonix | Embed | on | Parses the Next.js RSC/flight payload, `legacySrc`, `/api/stream/s/…` and Hydrax sources. Cartoons and movies. |
| Toonix Anime | Embed | on | Same resolver for anime titles; runs only when TMDB/OMDb genres include Animation/Anime. |
| 4KHDHub | File index | on | Reads the search cards and follows HubCloud/greenmotors links to R2 files. The site's search index is inconsistent. |
| FSLv1 | File index | on | VegaMovies Typesense search, then `nexdrive`/`fastdl` to a direct Google video URL. |
| FSLv2 | File index | on | Same FSL resolver pointed at RogMovies; useful when VegaMovies is down. |
| HindMoviez | File index | on | WordPress search plus HubCloud/greenmotors/links-index resolution. |
| HDHub4u | File index | on | Search page plus HubCloud, HubDrive, Pixeldrain and greenmotors. |
| MoviesDrive | File index | on | `mdrive` archives resolved through HubCloud workers. |
| MoviesMod | File index | on | WordPress search plus `?url=` wrapped download pages and `driveseed`/`gdflix` mirrors. |
| Bollyflix | File index | on | Bollywood and web-series search; follows `fastdlserver` → `gdflix`/`gdlink`, `driveseed`, `filepress` and `nexdrive` links. |
| MultiMovies | Embed | on | MultiMovies search; resolves Vidrift, VidBolt and VidOut directly and scans the remaining embeds for media. |
| VidOut | Embed | on | Reads the Watchout2025 GitHub HLS index directly: `/hls/movie/<tmdb>` for movies and `/hls/tv/<tmdb>/S<season>.json` for episodes. |

Enable or disable any of them in **Settings → Providers**. Each row has an on/off switch, a drag
handle and a 1–100 priority score. 100 runs first. If a server is slow or blocked, lower its score
or turn it off; the remaining sources still run. The health breaker also disables a repeatedly failing
server for five minutes, and **Maintenance → Reset source health** clears that state.

These resolvers do not bypass DRM, logins, or bot challenges. A Cloudflare interstitial is detected
and skipped rather than treated as an empty result. File-index sites sometimes publish only ZIP
archives; those are skipped because the player cannot open an archive as a video.

### Stremio / Nuvio addons (user supplied)

Add any addon base URL in settings, for example `https://addon.example`. CrunchyMe:

- normalises `stremio://` URLs,
- reads `/manifest.json` and checks that the addon supports `stream` for `movie`/`series`,
- requests `/stream/movie/<imdb>.json` or `/stream/series/<imdb>:<season>:<episode>.json`,
- keeps direct HTTP streams and skips torrents (`infoHash`), YouTube and external links,
- forwards `behaviorHints.proxyHeaders.request` headers.

No addon is bundled. You choose which addons to trust.

### Custom providers (user supplied)

Open **Settings → Custom providers** and add:

| Field | Example |
| --- | --- |
| Name | `My Provider` |
| Movie URL | `https://host.example/embed/{tmdb}` |
| TV URL | `https://host.example/embed/{tmdb}/{season}/{episode}` |
| Referer | `https://host.example/` |
| JSON | tick if the URL returns JSON instead of HTML |

Available tokens: `{tmdb}`, `{imdb}`, `{type}`, `{season}`, `{episode}`, `{title}`, `{year}`.
`{title}` is URL-encoded. A template that uses `{imdb}` is skipped when TMDB has no IMDB id.

Two resolution modes:

- **HTML/embed mode** (default): the page is scanned for `.m3u8` / `.mp4` / `.mpd` URLs
  (including JSON-escaped URLs), then same-origin iframes, then a `<video>` tag. If nothing is
  found, CloudStream's host-extractor registry is used as a fallback — this covers many common
  video hosts without writing code.
- **JSON mode**: every string in the payload is walked recursively for media URLs. Nearby
  `quality`, `height`, `language`, `size` fields are used for labels.

Bot-protection pages ("Just a moment…", `cf-chl`, "Attention Required") are detected and skipped
instead of being parsed as empty results.

## Adding a new built-in source

1. Add a `ServerSpec` to `ServerFarm.all` in `ServerFarm.kt` with the movie and TV URL templates.
   Set `kind = ServerKind.JSON` for an API endpoint or `ServerKind.EMBED` for a page.
   Use `{base}` plus a `domainKey` when the site changes domains often.
2. Add a resolver branch in `BuiltinServerSource.resolve` if the provider needs custom parsing.
   Otherwise the generic `JsonResolver` / `EmbedPage` handles it.
3. Add a parser test with a recorded fixture. Keep network code out of the parser so it stays
   JVM-testable.
4. Add the provider to the README table. The settings UI reads `ServerFarm.all`, so no UI edit is
   needed; new providers default to on unless `defaultEnabled = false`.

Domain overrides live in `ProviderDomains.kt`. The resolver reads a small JSON endpoint, follows
the provider landing page or `?re=` link, and falls back to the last known good host. If a site
moves, update the fallback there instead of editing every `ServerSpec`.

The important rule: sources return `Candidate`s, never CloudStream types. That keeps them
unit-testable on the JVM and keeps the CloudStream API confined to `CrunchyMeProvider`,
`CrunchyMePlugin` and `CloudStreamBridge`.

A minimal custom resolver:

```kotlin
private object MySource {
    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val url = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val body = Http.text(url, headersFor(spec), spec.timeoutSec.toLong()) ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return JsonWalk.harvest(root, url).map { hit ->
            Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = hit.url,
                kind = EmbedResolver.kindOf(hit.url),
                referer = spec.referer,
                qualityHint = hit.quality,
                audioLabel = Naming.displayLabel(hit.language, request.originalLanguage),
            )
        }
    }
}
```

## Reliability details

- **No WebView, no JavaScript.** Everything is HTTP + regex/JSON parsing. This is faster and
  cannot be fingerprinted as easily as a headless browser.
- **Progressive links.** The first source that finishes is emitted immediately; slower providers
  keep resolving in the background. The user can pick a link or skip loading without waiting for
  every provider.
- **Priority scheduling.** Providers start in the user's saved order. The default order puts
  MovieBox, Vidrift, VidLink, VidBolt and Vidly ahead of file-index and anime sources.
- **Per-source timeouts** (45 s farm, 12 s per addon, 6–20 s per HTTP call) and a circuit breaker
  that disables a source after four consecutive failures for five minutes.
- **Caching**: TMDB 15 min, resolved links 2 min (signed MovieBox URLs expire quickly), addon manifests 6 h. Re-opening an episode is
  instant and does not re-hit the network.
- **Declared evidence only.** Audio languages come from names, native scripts, ISO codes or
  BCP-47 tags. Bare two-letter codes are ignored in free text so titles like *It*, *Up* or *Us*
  are not mislabelled as Italian/Portuguese.
- **Unknown quality is kept.** A stream with no declared height is shown rather than hidden; the
  floor only removes streams that are provably too low.
- **Header forwarding.** `Referer` and addon-provided headers are attached to every emitted link.

## Build and test

Requirements: JDK 21 and Android SDK 36 (`local.properties` with `sdk.dir=...`).

```bash
./gradlew :CrunchyMe:make                   # build CrunchyMe.cs3
./gradlew :CrunchyMe:testDebugUnitTest      # unit + live tests
./gradlew make makePluginsJson              # build all plugins and plugins.json
```

Live tests read the key from `TMDB_API_KEY` or from `tmdb.api.key` in `local.properties`. If no
key is present the TMDB tests are skipped, not failed. The Archive test needs network access.

Test coverage:

| Test | What it proves |
| --- | --- |
| `RefsTest` | URL round-trips, IMDB-only URLs, cache keys |
| `NamingTest` | language aliases, multi-audio collapse, quality/size parsing, no false positives |
| `ManifestTest` | HLS height/audio/subtitles, DASH height/audio, URL resolution |
| `ProviderTemplatesTest` | JSON and line formats, token substitution, IMDB guard |
| `JsonWalkTest` | nested/array JSON, escaped URLs, relative resolution |
| `HtmlHarvestTest` | iframes, `<video>`, escaped URLs, challenge detection |
| `ArchiveParseTest` | search ranking, file filtering, URL encoding, size labels |
| `StremioParseTest` | URL normalisation, manifest capabilities, direct streams vs torrents |
| `ServerFarmTest` | unique ids, movie/TV URL templates, default server set |
| `VidBoltParseTest` | HLS/DASH sources, inline masters, audio labels, malformed payloads |
| `VidriftParseTest` | `embedMeta`, `orionStreams`, warm manifests, Evion fallback |
| `LiveTmdbTest` | real key, search, details, IMDB id, seasons/episodes, rows |
| `LiveArchiveTest` | real public-domain film resolves to 720p+ MP4 files |

## Limitations

- **No DRM.** CrunchyMe cannot play Netflix, Prime Video, Disney+, etc. Those streams are
  encrypted and require licensed decryption keys.
- **TMDB is metadata only.** It never provides video.
- **Internet Archive is movie-only** and mostly older/public-domain cinema.
- **Stremio addons must return direct HTTP streams.** Torrent-based addons are skipped.
- **Cloudflare-protected hosts** may refuse plain HTTP. The built-in Bingr resolver is the most
  likely to hit this; disable it and use another server or an addon that provides direct links.
  CrunchyMe does not try to bypass the challenge.
- Custom providers are the user's responsibility. Only use sources you are allowed to access.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Home rows are empty | Add a TMDB v3 key in CrunchyMe settings and tap **Test**, or switch **Catalog provider** to **IMDb**. |
| Search returns nothing | Same as above; check the key is the 32-character v3 key. |
| "No playable streams" | Check **Settings → Providers**; enable VidBolt/Vidrift/Vidly, add a Stremio addon, or add a custom provider. Not every title is available. |
| Only low quality appears | Lower the minimum quality; some archive files are 480p. |
| A source stopped working | **Reset health**, then **Clear caches**; the source may be temporarily blocked. |
| Build fails on `sdk.dir` | Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`. |

## License

GPL-3.0. See `LICENSE`.
