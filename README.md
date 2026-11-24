# CrunchyMe

CrunchyMe is a CloudStream 3 provider built around one idea: **metadata and playback are separate problems**.

TMDB provides the catalog, search, artwork and episode list. Playback is resolved by a small
"stream farm" that can run several independent sources in parallel and pick the best playable
result. The farm is deliberately pluggable: a legal public-domain archive is built in, Stremio/Nuvio
addons can be attached from settings, and new providers can be added as URL templates without
recompiling the plugin.

```
TMDB (metadata)  ──►  LinkRequest  ──►  Sources.collect()  ──►  MediaProbe  ──►  CloudStream player
                                         │
                                         ├── Internet Archive (built in, public domain)
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
| TMDB API key | Enables catalog, search, details and episodes. |
| Minimum quality | Drops streams whose height is *known* to be below the floor (480p / 720p / 1080p / Any). Unknown heights are kept. |
| Internet Archive | Built-in legal source for public-domain films. |
| Stremio / Nuvio addons | Enables the addon source. |
| Custom providers | Enable/disable or remove user-defined templates. |
| Clear caches | Drops TMDB, link and addon-manifest caches. |
| Reset health | Re-enables sources that were temporarily circuit-broken. |

## How a play request works

1. CloudStream calls `CrunchyMeProvider.loadLinks(data, ...)`.
2. `Refs.parse` extracts the TMDB id, IMDB id, type, season and episode from the URL. The URL is
   only an identifier — no provider website is scraped for metadata.
3. `Tmdb.detail` supplies the title, year and IMDB id (cached for 15 minutes).
4. `Sources.collect` fans out to every enabled source in parallel. Each source has a 30 second
   budget; a failing source cannot block the others.
5. `MediaProbe` inspects each candidate:
   - HLS: fetches the master playlist, reads `RESOLUTION`, `#EXT-X-MEDIA` audio languages and
     `TYPE=SUBTITLES` tracks.
   - DASH: reads `<Representation height>` and audio `lang`.
   - MP4/MKV: issues a `Range` request and parses the `moov → trak → tkhd` box for the real height.
6. Candidates below the quality floor (when the height is known) are dropped. The rest are
   deduplicated, sorted by height, cached for 10 minutes, and emitted with the right
   `Referer`/headers and subtitle tracks.

## Sources

### Internet Archive (built in)

Public-domain and openly licensed films with direct MP4 files. It is movie-only: archive.org
series metadata is not reliable enough to build an episode list from.

The resolver searches `advancedsearch.php`, ranks results by title and year, then reads
`archive.org/metadata/<id>` and keeps playable video files (`h.264`, `MPEG4`, `Matroska`, `WebM`,
`Ogg Video`). Trailers, samples, thumbnails and `.ia.` derivative files are filtered out.

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

1. Implement `StreamSource` in `CrunchyMe/src/main/kotlin/com/crunchyme/`:

```kotlin
object MySource : StreamSource {
    override val id = "my-source"
    override val name = "My Source"

    override suspend fun resolve(request: LinkRequest): List<Candidate> {
        val url = "https://api.example/play/${request.imdbId}"
        val body = Http.text(url, mapOf("Accept" to "application/json")) ?: return emptyList()
        return JsonWalk.harvest(org.json.JSONObject(body), url).map { hit ->
            Candidate(
                sourceId = id,
                sourceName = name,
                url = hit.url,
                kind = if (hit.url.contains(".m3u8")) MediaKind.HLS else MediaKind.FILE,
                qualityHint = hit.quality,
                audioLabel = hit.language,
            )
        }
    }
}
```

2. Add it to `Sources.collect` behind a setting and a `Health.isHealthy(...)` check.
3. Add a settings toggle in `Settings` + `SettingsUi`.
4. Add parser tests with a recorded fixture, and one live test guarded by `Assumptions`.

The important rule: sources return `Candidate`s, never CloudStream types. That keeps them
unit-testable on the JVM and keeps the CloudStream API confined to `CrunchyMeProvider`,
`CrunchyMePlugin` and `CloudStreamBridge`.

## Reliability details

- **No WebView, no JavaScript.** Everything is HTTP + regex/JSON parsing. This is faster and
  cannot be fingerprinted as easily as a headless browser.
- **Per-source timeouts** (30 s farm, 12 s per addon, 6–20 s per HTTP call) and a circuit breaker
  that disables a source after four consecutive failures for five minutes.
- **Caching**: TMDB 15 min, resolved links 10 min, addon manifests 6 h. Re-opening an episode is
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
| `LiveTmdbTest` | real key, search, details, IMDB id, seasons/episodes, rows |
| `LiveArchiveTest` | real public-domain film resolves to 720p+ MP4 files |

## Limitations

- **No DRM.** CrunchyMe cannot play Netflix, Prime Video, Disney+, etc. Those streams are
  encrypted and require licensed decryption keys.
- **TMDB is metadata only.** It never provides video.
- **Internet Archive is movie-only** and mostly older/public-domain cinema.
- **Stremio addons must return direct HTTP streams.** Torrent-based addons are skipped.
- **Cloudflare-protected hosts** may refuse plain HTTP. Use an addon that provides direct links
  instead of trying to bypass the challenge.
- Custom providers are the user's responsibility. Only use sources you are allowed to access.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Home rows are empty | Add a TMDB v3 key in CrunchyMe settings and tap **Test**. |
| Search returns nothing | Same as above; check the key is the 32-character v3 key. |
| "No playable streams" | Enable Internet Archive, add a Stremio addon, or add a custom provider. Not every title is public domain. |
| Only low quality appears | Lower the minimum quality; some archive files are 480p. |
| A source stopped working | **Reset health**, then **Clear caches**; the source may be temporarily blocked. |
| Build fails on `sdk.dir` | Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`. |

## License

GPL-3.0. See `LICENSE`.
