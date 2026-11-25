# MegaFlix

MegaFlix is a CloudStream meta-provider. It does not bundle other extensions and it does not scrape
their sites itself. At runtime it reads the providers already installed in CloudStream, runs their
own `search`, `load` and `loadLinks` methods, then presents one merged result page.

The point is coverage without copying code: when any installed provider is fixed or updated, MegaFlix
gets the fix automatically.

```
CloudStream provider registry
        │
        ├── Provider A ── search("Dune") ──┐
        ├── Provider B ── search("Dune") ──┼──► group by title/year/type ──► one MegaFlix result
        └── Provider C ── search("Dune") ──┘

MegaFlix result
        │
        ├── open result ──► A.load + B.load + C.load
        │                     └── merge episodes / metadata
        └── play ──────────► A.loadLinks + B.loadLinks + C.loadLinks
                              └── dedupe URLs, keep the best quality, prefix the source name
```

## Install

MegaFlix lives in the same repository as CrunchyMe. After CI publishes the `builds` branch, add the
repository to CloudStream:

1. **Settings → Extensions → Add repository**.
2. Paste `https://raw.githubusercontent.com/<owner>/<repo>/builds/repo.json`.
3. Refresh and install **MegaFlix**.

On first load MegaFlix adds the repositories listed in the official CloudStream repository database.
This is the same catalog used by the MegaRepo helper. After that, open **Settings → Extensions** and
refresh so CloudStream downloads the new extensions. MegaFlix will discover them automatically.

Auto-install is on by default. Turn it off under **MegaFlix → Settings → Home screen → Auto-install
repositories** if you prefer to manage repositories yourself.

## Settings

| Section | What it changes |
| --- | --- |
| Search depth | Fast, Balanced or Deep. Sets provider count, timeout and concurrency together. |
| Aggregate playable links | Ask every matched provider for streams. Turn off to use only the first matched provider. |
| Show source count | Appends `· N sources` to a result title when more than one provider has it. |
| Include catalog-only providers | Adds metadata providers (TMDB, Trakt, etc.) to the search pool. |
| Include adult results | Keeps NSFW results from providers that expose them. |
| Provider limit | Maximum providers contacted per search. |
| Per-provider timeout | A slow provider is skipped instead of blocking the search. |
| Parallel requests | Number of providers contacted at the same time. |
| Sources per title | Maximum provider versions merged into one result page. |
| Providers | Per-provider block list. New providers are enabled by default; `Enable all` clears the block list. |
| Home screen | Number of home rows borrowed from installed providers. `0` hides them. |
| Repository catalog | Install every repository from the official CloudStream catalog. |
| About & diagnostics | Provider counts, failures and a health reset. |

## How a request works

### Search

`MegaFlixProvider.search` gets a snapshot of `APIHolder.allProviders`, removes itself, applies the
allow list and the catalog-only filter, then calls each provider with a bounded coroutine and a
timeout. Results are grouped by normalized title, year and type. One provider contributes at most
one URL to a group. The group URL is an opaque `https://megaflix.local/...` value containing the
provider names, their `mainUrl` values and their own result URLs.

### Load

Opening a result asks every matched provider to load its own URL in parallel. For movies and live
streams the data URLs are kept as playable-link references. For series, episodes are merged by
season and episode number; each merged episode holds references to every provider that has it. The
first provider with a usable response supplies the poster, plot, cast and other metadata. The plot
also names the matched providers.

### Play

CloudStream calls MegaFlix's `loadLinks` with either a merged movie link reference or a merged
episode reference. MegaFlix asks every referenced provider for links, deduplicates by URL, keeps the
highest quality copy, and re-emits the links with the provider name in the source label. Subtitles
are deduplicated by URL and language. The provider's own `Referer` and headers are preserved.

### Blocking and failures

MegaFlix does not bypass access controls. If a provider is blocked by Cloudflare, a JavaScript
challenge, a login, a CAPTCHA, a geo restriction or DRM, that provider's own code must handle it.

- Cloudflare/JS: providers that use `app.get` may get an interstitial; providers that use
  CloudStream's WebView helper can solve it themselves. MegaFlix just sees a failed response.
- Login/CAPTCHA: MegaFlix cannot inject credentials or solve a challenge. Use a provider that
  supports the site's normal access path.
- Geo restriction: use a provider or network that is allowed in your region.
- DRM: MegaFlix forwards the provider's `ExtractorLink`, including its DRM information. Whether the
  player can play it depends on the provider and the device.
- Timeouts: a blocked provider is skipped after the configured timeout. Its failure count rises in
  diagnostics; other providers still return results.

## Adding providers

There are two ways, and neither requires editing MegaFlix.

### Install an existing extension

1. Add the repository under **Settings → Extensions → Add repository**.
2. Refresh and install the extension.
3. Open **MegaFlix → Settings → Providers** and make sure it is enabled.
4. Open **MegaFlix → Settings → Repository catalog** if you want to add the whole official catalog
   at once.

### Write a new provider

Implement a normal CloudStream `MainAPI` and register it from a `@CloudstreamPlugin` class. MegaFlix
discovers it from `APIHolder`, so it appears in the provider list on the next load.

Minimum useful implementation:

```kotlin
@CloudstreamPlugin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
    }
}

class MyProvider : MainAPI() {
    override var name = "MyProvider"
    override var mainUrl = "https://example.com"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Return newSearchResponseList(...)
    }

    override suspend fun load(url: String): LoadResponse? {
        // Return newMovieLoadResponse(...) or newTvSeriesLoadResponse(...)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Resolve data to playable ExtractorLink values and call callback(...)
    }
}
```

For a provider that only supplies metadata, turn on **Include catalog-only providers** or add its
name to the provider list. MegaFlix filters a small set of known catalog providers by default so
they do not flood search results.

## Build and test

```bash
./gradlew :MegaFlix:test
./gradlew :MegaFlix:make
```

The plugin artifact is written to `MegaFlix/build/MegaFlix.cs3`. The tests cover reference encoding,
title normalization/grouping, provider aggregation, episode merging and the repository catalog
parser. They do not need an emulator.

## Limits

- MegaFlix can only use providers that are installed and working in CloudStream.
- Providers with duplicate names can be ambiguous; MegaFlix stores `mainUrl` in every reference and
  resolves by name plus URL first.
- A provider that returns a series as a movie, or a movie as a series, may need its own fix.
- Some providers only implement `load` and return no `loadLinks`; those show no playable links.
- Repository installation does not force an immediate CloudStream extension refresh.
- MegaFlix is not a source of content and does not grant rights to any content. Use providers and
  content you are allowed to access.
