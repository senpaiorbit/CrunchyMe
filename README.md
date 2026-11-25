# CrunchyMe

CrunchyMe is a CloudStream 3 provider built around one idea: **metadata and playback are separate problems**.

TMDB or IMDb/OMDb provides the catalog and search. TheTVDB fills in episode names, stills and
runtimes when available, and BetterPosters (btttr.cc) supplies optional alternate artwork. Playback
is resolved by a small "stream farm" that runs independent sources in parallel and picks the best
playable result. Providers are grouped into **Streaming**, **File index** and **Anime & animation**
sections, each with its own priority scale and worker allocation. The farm is deliberately
pluggable: a legal public-domain archive is built in, Stremio/Nuvio addons can be attached from
settings, and new providers can be added as URL templates without recompiling the plugin.

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
includes a build tag. Plugin downloads use `github.com/<repo>/raw/...` rather than
`raw.githubusercontent.com`, so CloudStream's optional jsDelivr proxy cannot serve an old cached
`.cs3` whose bytes no longer match `fileHash`.

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
| Provider sections | **Streaming**, **File index** and **Anime & animation** are ordered independently. Each section has its own priority scale from 1 to the number of providers in that section; the highest number starts first. Drag inside a section or type the number. Changing priority alone does not clear cached links. |
| Parallel tasks | Total workers, 2–10 (default 8). The default split is 2 streaming, 2 file index and 4 anime. Anime workers only start when TMDB/OMDb genres include Animation or Anime; otherwise the split is 2 streaming and 6 file index. Extra configured workers go to file index first. |
| Better posters | Off by default. When enabled, uses btttr.cc BetterPosters artwork for cards and details; the default remains TMDB/OMDb posters. The home carousel uses the title logo; other rows use posters. |
| TheTVDB episodes | On by default. Uses TheTVDB v4 English translations for episode names, stills and runtimes, falling back to TMDB/OMDb when a series or translation is missing. |
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
3. `Tmdb.detail` supplies the title, year, IMDB id and TVDB id (cached for 15 minutes). For TV
   titles, `Tvdb.episodes` adds episode names, stills and runtimes; TMDB/OMDb is the fallback.
4. `Sources.stream` splits enabled providers into Streaming, File index and Anime queues. It starts
   2 streaming workers, 2 file-index workers and (for Animation/Anime titles) 4 anime workers by
   default. Each source has its own timeout; a failing source cannot block the others. Results are
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

## Metadata and artwork

- **TMDB** is the default catalog. It supplies search, home rows, cast, trailers, logos and the
  external IDs used by the other services.
- **TheTVDB v4** is used for TV episode metadata. The provider reads the TVDB id from TMDB
  `external_ids`, then falls back to a TVDB remote-id search by TMDB or IMDb id. Series and episode
  requests use the `eng` translation, so anime titles do not fall back to Japanese names and
  overviews. TMDB's `en-US` title is preferred; when it is still CJK, the provider uses TMDB's
  English translation or the TVDB English value. If TheTVDB has no series, translation or episode
  data, the TMDB/OMDb episode list is used unchanged. Episodes are always grouped by a positive
  season number, so single-season and absolute-numbered shows still render as a season instead of
  one flat stack.
- **BetterPosters (btttr.cc)** is off by default. When enabled, the meta endpoint accepts
  `tmdb:<id>`, so cards do not need an extra IMDb lookup. If btttr has no artwork, the TMDB/OMDb
  poster is used.
- **TheIntroDB** intro/recap/credits/preview skipping is built into CloudStream. CrunchyMe adds
  both the TMDb and IMDb ids to `LoadResponse`, so the app's skip lookup works. The public
  `api.theintrodb.org/v4/media` endpoint does not require the API key for reads; the key is only
  needed for account/submission features.

## Sources

### Internet Archive (built in)

Public-domain and openly licensed films with direct MP4 files. It is movie-only: archive.org
series metadata is not reliable enough to build an episode list from.

The resolver searches `advancedsearch.php`, ranks results by title and year, then reads
`archive.org/metadata/<id>` and keeps playable video files (`h.264`, `MPEG4`, `Matroska`, `WebM`,
`Ogg Video`). Trailers, samples, thumbnails and `.ia.` derivative files are filtered out.

### Built-in servers

The provider ships with twenty-six optional servers in three sections. Each section runs in its own
priority order, with its own workers, and every source has a timeout and circuit breaker. The
Anime & animation section is skipped entirely when the title has no Animation/Anime genre.

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
| Toonix | Embed | on | Parses the Next.js RSC/flight payload, `legacySrc`, `/api/stream/s/…` and Hydrax sources. Cartoons and animation; runs only for Animation/Anime genres. |
| Toonix Anime | Embed | on | Same resolver for anime titles; runs only when TMDB/OMDb genres include Animation/Anime. |
| 4KHDHub | File index | on | Reads the search cards and follows HubCloud/greenmotors links to R2 files. The site's search index is inconsistent. |
| FSLv1 | File index | on | VegaMovies Typesense search, then `nexdrive`/`vcloud`/`fastdl`. V-Cloud R2 links are preferred because they stream; the fastdl Google video URL is the fallback. |
| FSLv2 | File index | on | Same FSL resolver pointed at RogMovies; useful when VegaMovies is down. |
| HindMoviez | File index | on | WordPress search plus HubCloud/greenmotors/links-index resolution. |
| HDHub4u | File index | on | Search page plus HubCloud, HubDrive, Pixeldrain and greenmotors. |
| MoviesDrive | File index | on | `mdrive` archives resolved through HubCloud workers. |
| MoviesMod | File index | on | WordPress search plus `?url=` wrapped download pages and `driveseed`/`gdflix` mirrors. |
| Bollyflix | File index | on | Bollywood and web-series search; follows `fastdlserver` → `gdflix`/`gdlink`, `driveseed`, `filepress` and `nexdrive` links. |
| MultiMovies | Embed | on | MultiMovies search; resolves Vidrift, VidBolt and VidOut directly and scans the remaining embeds for media. |
| VidOut | Embed | on | Reads the Watchout2025 GitHub HLS index directly: `/hls/movie/<tmdb>` for movies and `/hls/tv/<tmdb>/S<season>.json` for episodes. |

Enable or disable any of them in **Settings → Providers**. Providers are split into Streaming,
File index and Anime & animation cards. Each row has an on/off switch, a drag handle, the site
favicon and a local priority from 1 to the section size. The highest number runs first. For an
Animation/Anime title the Anime & animation queue is started before Streaming, so anime-specific
sources get the first chance to return links. If a server is slow or blocked, lower its priority or
turn it off; the remaining sources still run. The health breaker also disables a repeatedly failing
server for five minutes, and **Maintenance → Reset source health** clears that state.

These resolvers do not bypass DRM, logins, or bot challenges. A Cloudflare interstitial is detected
and skipped rather than treated as an empty result. File-index sites sometimes publish only ZIP
archives; those are skipped because the player cannot open an archive as a video.

### What makes a video playable, and what gets blocked

CrunchyMe does not host video. A source resolver returns a candidate URL plus headers; the
provider then decides whether the URL is a real playable stream:

- **HLS (`.m3u8`) and DASH (`.mpd`)** are parsed first. `MediaProbe` reads the master playlist or
  MPD, extracts heights, audio languages and subtitle tracks, and rejects manifests that contain no
  media segments.
- **Direct files (`.mp4`, `.mkv`, `.webm`)** get a short `Range` request and a `moov → trak → tkhd`
  parse to discover the real height without downloading the film.
- **Referer and headers** are preserved from the resolver. Many CDNs return 403 without them.
- **Cloudflare / bot challenges** are detected by page text and skipped. CrunchyMe does not try to
  bypass a challenge, DRM, a login or a paywall. If a site only offers a ZIP/RAR, the player cannot
  open it and the candidate is dropped.
- **Host extractors** are used as a fallback for embed pages that hide a stream behind a known
  player. If no extractor or manifest is found, the source simply returns no links and the health
  breaker eventually rests it.

A source that is blocked for one title may work for another; the per-source timeout and circuit
breaker keep a dead host from delaying the rest of the farm.

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
   Set `category = ServerCategory.STREAMING`, `FILE_INDEX` or `ANIMATION`; animation providers
   only run for Animation/Anime titles. Use `{base}` plus a `domainKey` when the site changes
   domains often.
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
- **Priority scheduling.** Each section starts in its own saved order. Streaming starts with
  MovieBox, Vidrift, VidLink, VidBolt and Vidly; file index and anime have independent scales.
- **Section workers.** 2 streaming, 2 file index and 4 anime by default. For Animation/Anime
  titles the anime queue starts first; without an animation genre the anime queue is skipped and
  file index gets 6 workers.
- **Per-source timeouts** (45 s farm, 12 s per addon, 6–20 s per HTTP call) and a circuit breaker
  that disables a source after four consecutive failures for five minutes.
- **Fewer duplicate requests.** FSL expands V-Cloud and fastdl concurrently, candidates are probed
  once instead of retrying the whole batch when a quality floor hides them, and OkHttp reuses one
  client/connection pool instead of rebuilding a client for every request.
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
