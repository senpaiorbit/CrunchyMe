@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.crunchyme

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val SURFACE = Color.parseColor("#0F0F0F")
private val SURFACE_LOW = Color.parseColor("#1F1F1F")
private val SURFACE_HIGH = Color.parseColor("#2A2A2A")
private val BORDER = Color.parseColor("#3F3F3F")
private val TEXT = Color.parseColor("#F1F1F1")
private val TEXT_DIM = Color.parseColor("#AAAAAA")
private val TEXT_MUTED = Color.parseColor("#909090")
private val TEXT_FAINT = Color.parseColor("#717171")
private val CONTROL = Color.parseColor("#E4E4E4")
private val ON_CONTROL = Color.parseColor("#0F0F0F")
private val WARNING = Color.parseColor("#FFB4AB")

object SettingsUi {

    private enum class Section(val title: String, val subtitle: String, val index: String) {
        PLAYBACK("Playback", "TMDB key, quality floor, built-in toggles", "01"),
        PROVIDERS("Providers", "Built-in stream servers", "02"),
        ADDONS("Addons", "Stremio and Nuvio manifests", "03"),
        CUSTOM("Custom sources", "URL templates and JSON endpoints", "04"),
        MAINTENANCE("Maintenance", "Caches, health, and storage", "05"),
    }

    private class SheetDialog(context: Context) :
        Dialog(context, android.R.style.Theme_Material_NoActionBar) {

        var onBack: (() -> Boolean)? = null

        @Suppress("DEPRECATION")
        override fun onBackPressed() {
            if (onBack?.invoke() != true) super.onBackPressed()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && onBack?.invoke() != true) {
                    super.onBackPressed()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    fun open(context: Context) {
        Settings.init(context)
        val ui = Ui(context)
        val dialog = try {
            SheetDialog(context)
        } catch (t: Throwable) {
            ui.toast("Settings unavailable in this context")
            return
        }

        val root = FrameLayout(context).apply { setBackgroundColor(SURFACE) }
        var current: Section? = null
        var renderHome: () -> Unit = {}
        var renderDetail: (Section) -> Unit = {}

        renderHome = {
            current = null
            root.removeAllViews()
            val page = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(24))
                setBackgroundColor(SURFACE)
            }

            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(ui.label("CRUNCHYME", 11f, TEXT_FAINT, medium = true, spacing = 0.16f))
            top.addView(ui.spacer())
            top.addView(ui.textButton("×", TEXT_DIM, 24f) { dialog.dismiss() })
            page.addView(top)

            page.addView(
                ui.label("Settings", 30f, TEXT, medium = true).apply {
                    setPadding(0, ui.dp(18), 0, ui.dp(6))
                },
            )
            page.addView(ui.label("Catalog, providers, and playback.", 14f, TEXT_DIM))
            page.addView(ui.divider(top = ui.dp(22), bottom = ui.dp(18)))

            val providers = Settings.enabledServers().size
            page.addView(ui.tile(Section.PLAYBACK) { renderDetail(Section.PLAYBACK) })
            page.addView(ui.tile(Section.PROVIDERS, "$providers of ${ServerFarm.all.size} on") {
                renderDetail(Section.PROVIDERS)
            })
            page.addView(ui.tile(Section.ADDONS, addonSummary()) { renderDetail(Section.ADDONS) })
            page.addView(ui.tile(Section.CUSTOM, customSummary()) { renderDetail(Section.CUSTOM) })
            page.addView(ui.tile(Section.MAINTENANCE) { renderDetail(Section.MAINTENANCE) })

            page.addView(
                ui.label("Settings are stored on this device.", 11f, TEXT_FAINT).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(18), 0, 0)
                },
            )
            root.addView(
                ui.pageScroll(page),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            ui.animatePage(page)
        }

        fun resetSection(section: Section) {
            when (section) {
                Section.PLAYBACK -> {
                    Settings.saveQualityFloor(Settings.DEFAULT_FLOOR)
                    Settings.saveArchiveEnabled(true)
                    Settings.saveStremioEnabled(true)
                }
                Section.PROVIDERS -> ServerFarm.all.forEach {
                    Settings.saveServerEnabled(it.id, it.defaultEnabled)
                }
                else -> Unit
            }
            renderDetail(section)
        }

        renderDetail = { section ->
            current = section
            root.removeAllViews()
            val page = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(SURFACE)
            }

            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(10), ui.dp(8), ui.dp(16), ui.dp(8))
            }
            bar.addView(ui.textButton("←", TEXT, 24f) { renderHome() })
            val title = ui.label(section.title, 22f, TEXT, medium = true).apply {
                setPadding(ui.dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            bar.addView(title)
            if (section == Section.PLAYBACK || section == Section.PROVIDERS) {
                bar.addView(ui.textButton("Reset", TEXT_DIM) { resetSection(section) })
            }
            page.addView(bar)
            page.addView(ui.divider())

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(30))
            }

            when (section) {
                Section.PLAYBACK -> playback(ui, body) { renderDetail(it) }
                Section.PROVIDERS -> providers(ui, body) { renderDetail(it) }
                Section.ADDONS -> addons(ui, body) { renderDetail(it) }
                Section.CUSTOM -> custom(ui, body) { renderDetail(it) }
                Section.MAINTENANCE -> maintenance(ui, body)
            }

            page.addView(
                ui.pageScroll(body),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                ),
            )
            root.addView(page)
            ui.animatePage(page)
        }

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dialog.onBack = {
            if (current != null) {
                renderHome()
                true
            } else {
                false
            }
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(SURFACE))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0f)
        dialog.window?.statusBarColor = SURFACE
        dialog.window?.navigationBarColor = SURFACE
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        renderHome()
        val shown = runCatching { dialog.show() }.isSuccess
        if (!shown) {
            ui.toast("Settings unavailable in this context")
            return
        }
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun playback(ui: Ui, body: LinearLayout, rerender: (Section) -> Unit) {
        val keyCard = ui.card()
        keyCard.addView(ui.sectionLabel("TMDB V3 KEY"))
        val saved = Settings.tmdbKey()
        keyCard.addView(
            ui.label(
                if (saved == null) "No key saved. Search and catalog stay empty." else "Saved · ${mask(saved)}",
                13f, TEXT_DIM,
            ).apply { setPadding(0, ui.dp(6), 0, ui.dp(10)) },
        )
        val (secretRoot, keyInput) = ui.secret("", "32-character API key")
        keyCard.addView(secretRoot)

        val keyActions = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, 0)
        }
        keyActions.addView(ui.action("Save", true) {
            val value = keyInput.text.toString()
            if (!Settings.isValidTmdbKey(value)) {
                ui.toast("That does not look like a TMDB v3 key")
            } else {
                Settings.saveTmdbKey(value)
                Tmdb.clearCache()
                ui.toast("Key saved")
                rerender(Section.PLAYBACK)
            }
        })
        keyActions.addView(ui.action("Test", false) {
            val value = keyInput.text.toString().ifBlank { Settings.tmdbKey().orEmpty() }
            if (value.isBlank()) {
                ui.toast("Enter a key first")
            } else {
                ui.toast("Testing key…")
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val ok = Tmdb.testKey(value)
                    withContext(Dispatchers.Main) {
                        ui.toast(if (ok) "Key works" else "Key rejected or network unavailable")
                    }
                }
            }
        })
        keyActions.addView(ui.action("Get key", false) { ui.openUrl(Settings.TMDB_LINK) })
        keyCard.addView(keyActions)
        body.addView(keyCard)

        val imdbCard = ui.card()
        imdbCard.addView(ui.sectionLabel("IMDb CATALOG · OMDb"))
        imdbCard.addView(
            ui.label(
                "Search, IMDb IDs, posters and TV episode lists work without a TMDB key. " +
                    "TMDB still powers the trending rows.",
                13f, TEXT_DIM,
            ).apply { setPadding(0, ui.dp(6), 0, ui.dp(10)) },
        )
        val omdbKey = Settings.omdbKey()
        imdbCard.addView(
            ui.label(
                if (omdbKey == Omdb.DEFAULT_KEY) "Built-in key active · ${mask(omdbKey)}"
                else "Custom key · ${mask(omdbKey)}",
                12f, TEXT_FAINT,
            ).apply { setPadding(0, 0, 0, ui.dp(8)) },
        )
        val (omdbRoot, omdbInput) = ui.secret("", "OMDb API key")
        imdbCard.addView(omdbRoot)
        val omdbActions = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, 0)
        }
        omdbActions.addView(ui.action("Save", true) {
            val value = omdbInput.text.toString()
            if (!Settings.isValidOmdbKey(value)) {
                ui.toast("OMDb keys are 6–32 letters or digits")
            } else {
                Settings.saveOmdbKey(value)
                ui.toast("IMDb catalog key saved")
                rerender(Section.PLAYBACK)
            }
        })
        omdbActions.addView(ui.action("Test", false) {
            val value = omdbInput.text.toString().ifBlank { Settings.omdbKey() }
            ui.toast("Testing OMDb…")
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val ok = Omdb.testKey(value)
                withContext(Dispatchers.Main) {
                    ui.toast(if (ok) "OMDb works" else "OMDb rejected the key")
                }
            }
        })
        omdbActions.addView(ui.action("Get key", false) { ui.openUrl(Settings.OMDB_LINK) })
        imdbCard.addView(omdbActions)
        body.addView(imdbCard)

        val qualityCard = ui.card()
        qualityCard.addView(ui.sectionLabel("QUALITY FLOOR"))
        qualityCard.addView(
            ui.label("Hide streams below this height. Unknown quality stays visible.", 13f, TEXT_DIM)
                .apply { setPadding(0, ui.dp(6), 0, ui.dp(12)) },
        )
        val chips = LinearLayout(ui.context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0 to "Any", 480 to "480p", 720 to "720p", 1080 to "1080p").forEach { (height, name) ->
            chips.addView(ui.chip(name, Settings.qualityFloor() == height) {
                Settings.saveQualityFloor(height)
                rerender(Section.PLAYBACK)
            })
        }
        qualityCard.addView(chips)
        body.addView(qualityCard)

        val sourcesCard = ui.card()
        sourcesCard.addView(ui.sectionLabel("BUILT-IN SOURCES"))
        sourcesCard.addView(
            ui.toggleRow("Internet Archive", "Public-domain films, direct MP4", Settings.archiveEnabled()) {
                Settings.saveArchiveEnabled(it)
            },
        )
        sourcesCard.addView(ui.divider(top = ui.dp(6), bottom = ui.dp(6)))
        sourcesCard.addView(
            ui.toggleRow("Stremio / Nuvio", "Manifests added from the Addons section", Settings.stremioEnabled()) {
                Settings.saveStremioEnabled(it)
            },
        )
        body.addView(sourcesCard)
    }

    private fun providers(ui: Ui, body: LinearLayout, rerender: (Section) -> Unit) {
        val enabled = Settings.enabledServers().size
        val intro = ui.card()
        intro.addView(ui.label("Stream farm", 17f, TEXT, medium = true))
        intro.addView(
            ui.label(
                "$enabled of ${ServerFarm.all.size} servers on. Fast providers run by default; " +
                    "file-index and anime providers can be enabled below.",
                13f, TEXT_DIM,
            ).apply { setPadding(0, ui.dp(6), 0, ui.dp(12)) },
        )
        val actions = LinearLayout(ui.context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ui.action("Enable all", false) {
            Settings.saveAllServers(true)
            rerender(Section.PROVIDERS)
        })
        actions.addView(ui.action("Disable all", false) {
            Settings.saveAllServers(false)
            rerender(Section.PROVIDERS)
        })
        intro.addView(actions)
        body.addView(intro)

        val filterCard = ui.card()
        filterCard.addView(ui.sectionLabel("FIND A SERVER"))
        val filter = ui.field("", "Name, language, or type")
        filterCard.addView(filter)
        body.addView(filterCard)

        val rows = mutableListOf<Pair<LinearLayout, String>>()
        val groups = listOf(
            "STREAMING" to ServerFarm.all.filter { it.speed != ServerSpeed.SLOW && !it.animeOnly },
            "FILE INDEX" to ServerFarm.all.filter { it.speed == ServerSpeed.SLOW && !it.animeOnly },
            "ANIME" to ServerFarm.all.filter { it.animeOnly },
        )
        var animationIndex = 0
        for ((heading, specs) in groups) {
            if (specs.isEmpty()) continue
            val card = ui.card()
            card.addView(ui.sectionLabel("$heading · ${specs.size}"))
            specs.forEachIndexed { index, spec ->
                if (index > 0) card.addView(ui.divider(top = ui.dp(5), bottom = ui.dp(5)))
                val badges = buildList {
                    if (spec.supportsTv) add("TV")
                    if (spec.supports4k) add("4K")
                    if (spec.hasSubtitles) add("Subs")
                    if (spec.animeOnly) add("Anime")
                }
                val speed = spec.speed.name.lowercase().replace('_', ' ')
                val subtitle = "${spec.note} · $speed · ${if (spec.defaultEnabled) "default" else "optional"}"
                val row = ui.toggleRow(
                    spec.name,
                    subtitle,
                    Settings.serverEnabled(spec.id),
                    badges,
                ) { on -> Settings.saveServerEnabled(spec.id, on) }
                card.addView(row)
                rows += row to "${spec.name} ${spec.note} ${spec.speed.name}".lowercase()
                ui.animateIn(row, (animationIndex++ * 18L).coerceAtMost(240L))
            }
            body.addView(card)
        }

        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                rows.forEach { (row, key) ->
                    row.visibility = if (query.isEmpty() || key.contains(query)) View.VISIBLE else View.GONE
                }
            }
        })

        val note = ui.card()
        note.addView(
            ui.label(
                "These servers return public embed pages or JSON. Nothing here bypasses DRM, " +
                    "logins, or bot challenges. TV requests use season and episode when the server supports it.",
                12f, TEXT_FAINT,
            ),
        )
        body.addView(note)
    }

    private fun addons(ui: Ui, body: LinearLayout, rerender: (Section) -> Unit) {
        val card = ui.card()
        card.addView(ui.sectionLabel("STREMIO / NUVIO"))
        val addons = Settings.addonUrls()
        if (addons.isEmpty()) {
            card.addView(
                ui.label("No addons yet.", 13f, TEXT_DIM).apply {
                    setPadding(0, ui.dp(8), 0, ui.dp(8))
                },
            )
        } else {
            addons.forEachIndexed { index, url ->
                if (index > 0) card.addView(ui.divider(top = ui.dp(4), bottom = ui.dp(4)))
                val row = LinearLayout(ui.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(6), 0, ui.dp(6))
                }
                row.addView(
                    ui.label(url, 12f, TEXT_MUTED).apply {
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                row.addView(ui.textButton("×", WARNING, 20f) {
                    Settings.removeAddon(url)
                    StremioSource.clearCache()
                    rerender(Section.ADDONS)
                })
                card.addView(row)
            }
        }
        body.addView(card)

        val addCard = ui.card()
        addCard.addView(ui.sectionLabel("ADD MANIFEST"))
        addCard.addView(
            ui.label("Base URL or full /manifest.json URL.", 13f, TEXT_DIM).apply {
                setPadding(0, ui.dp(6), 0, ui.dp(10))
            },
        )
        val input = ui.field("", "https://addon.example")
        addCard.addView(input)
        addCard.addView(ui.action("Add addon", true) {
            val added = Settings.addAddon(input.text.toString())
            if (added == null) {
                ui.toast("Invalid or duplicate addon URL")
            } else {
                ui.toast("Addon added")
                rerender(Section.ADDONS)
            }
        })
        body.addView(addCard)
    }

    private fun custom(ui: Ui, body: LinearLayout, rerender: (Section) -> Unit) {
        val savedCard = ui.card()
        savedCard.addView(ui.sectionLabel("SAVED TEMPLATES"))
        val providers = Settings.providers()
        if (providers.isEmpty()) {
            savedCard.addView(
                ui.label("No custom sources.", 13f, TEXT_DIM).apply {
                    setPadding(0, ui.dp(8), 0, ui.dp(8))
                },
            )
        } else {
            providers.forEachIndexed { index, provider ->
                if (index > 0) savedCard.addView(ui.divider(top = ui.dp(6), bottom = ui.dp(6)))
                val row = LinearLayout(ui.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val texts = LinearLayout(ui.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                texts.addView(ui.label(provider.name, 15f, TEXT, medium = true))
                texts.addView(
                    ui.label(if (provider.json) "JSON endpoint" else "Embed page", 12f, TEXT_FAINT)
                        .apply { setPadding(0, ui.dp(2), 0, 0) },
                )
                row.addView(texts)
                row.addView(ui.toggle(provider.enabled) { on ->
                    Settings.setProviderEnabled(provider.name, on)
                })
                row.addView(ui.textButton("×", WARNING, 20f) {
                    Settings.removeProvider(provider.name)
                    rerender(Section.CUSTOM)
                })
                savedCard.addView(row)
            }
        }
        body.addView(savedCard)

        val form = ui.card()
        form.addView(ui.sectionLabel("NEW TEMPLATE"))
        form.addView(
            ui.label(
                "{tmdb} {imdb} {type} {season} {episode} {title} {year}",
                11f, TEXT_FAINT,
            ).apply {
                typeface = Typeface.MONOSPACE
                setPadding(0, ui.dp(6), 0, ui.dp(10))
            },
        )
        val name = ui.field("", "Name")
        val movie = ui.field("", "Movie URL")
        val tv = ui.field("", "TV URL (optional)")
        val referer = ui.field("", "Referer (optional)")
        var isJson = false
        form.addView(ui.fieldLabel("NAME"))
        form.addView(name)
        form.addView(ui.fieldLabel("MOVIE URL"))
        form.addView(movie)
        form.addView(ui.fieldLabel("TV URL"))
        form.addView(tv)
        form.addView(ui.fieldLabel("REFERER"))
        form.addView(referer)
        form.addView(ui.toggleRow("URL returns JSON", "Parse nested JSON instead of an embed page", false) {
            isJson = it
        })
        form.addView(ui.action("Add provider", true) {
            val providerName = name.text.toString().trim()
            val movieUrl = movie.text.toString().trim()
            if (providerName.isEmpty() || movieUrl.isEmpty()) {
                ui.toast("Name and movie URL are required")
            } else if (Settings.providers().any { it.name.equals(providerName, true) }) {
                ui.toast("A provider with that name already exists")
            } else {
                Settings.saveProviders(
                    Settings.providers() + ProviderTemplate(
                        name = providerName,
                        movieUrl = movieUrl,
                        tvUrl = tv.text.toString().trim(),
                        referer = referer.text.toString().trim().ifBlank { null },
                        json = isJson,
                    ),
                )
                ui.toast("Provider added")
                rerender(Section.CUSTOM)
            }
        })
        body.addView(form)
    }

    private fun maintenance(ui: Ui, body: LinearLayout) {
        val cacheCard = ui.card()
        cacheCard.addView(ui.sectionLabel("CACHES"))
        cacheCard.addView(
            ui.label("TMDB lookups, resolved links, and addon manifests.", 13f, TEXT_DIM)
                .apply { setPadding(0, ui.dp(6), 0, ui.dp(12)) },
        )
        cacheCard.addView(ui.action("Clear caches", false) {
            LinkCache.clear()
            Tmdb.clearCache()
            StremioSource.clearCache()
            ui.toast("Caches cleared")
        })
        body.addView(cacheCard)

        val healthCard = ui.card()
        healthCard.addView(ui.sectionLabel("SOURCE HEALTH"))
        healthCard.addView(
            ui.label("Re-enable servers that tripped the circuit breaker.", 13f, TEXT_DIM)
                .apply { setPadding(0, ui.dp(6), 0, ui.dp(12)) },
        )
        healthCard.addView(ui.action("Reset source health", false) {
            Health.reset()
            ui.toast("Source health reset")
        })
        body.addView(healthCard)

        val about = ui.card()
        about.addView(ui.sectionLabel("ABOUT"))
        about.addView(ui.label("CrunchyMe", 17f, TEXT, medium = true).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        about.addView(
            ui.label(
                "TMDB metadata · built-in servers · Stremio addons · custom templates",
                12f, TEXT_FAINT,
            ).apply { setPadding(0, ui.dp(4), 0, 0) },
        )
        body.addView(about)
    }

    private fun addonSummary(): String {
        val count = Settings.addonUrls().size
        return if (count == 0) "No manifests" else "$count manifest${if (count == 1) "" else "s"}"
    }

    private fun customSummary(): String {
        val count = Settings.providers().size
        return if (count == 0) "No templates" else "$count template${if (count == 1) "" else "s"}"
    }

    private fun mask(key: String): String = "${key.take(4)}…${key.takeLast(4)}"

    private class Ui(val context: Context) {
        private val density = context.resources.displayMetrics.density

        fun dp(value: Number): Int = (value.toFloat() * density).roundToInt()

        fun label(
            value: String,
            size: Float,
            color: Int,
            medium: Boolean = false,
            spacing: Float = 0f,
        ): TextView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (spacing > 0f) letterSpacing = spacing
            includeFontPadding = false
        }

        fun rounded(color: Int, radius: Number, stroke: Number = 0, strokeColor: Int = BORDER): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
            }

        fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        fun ripple(content: android.graphics.drawable.Drawable): RippleDrawable =
            RippleDrawable(ColorStateList.valueOf(Color.argb(28, 255, 255, 255)), content, null)

        fun inputFill(focused: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (focused) SURFACE_HIGH else SURFACE_LOW)
            cornerRadius = dp(14).toFloat()
            if (focused) setStroke(dp(2), CONTROL) else setStroke(dp(1), BORDER)
        }

        fun inputBackground(): StateListDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), inputFill(true))
            addState(intArrayOf(), inputFill(false))
        }

        fun spacer(): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        fun animatePage(view: View) {
            view.alpha = 0f
            view.translationY = dp(10).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(190).start()
        }

        fun animateIn(view: View, delay: Long) {
            view.alpha = 0f
            view.translationY = dp(8).toFloat()
            view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(220).start()
        }

        fun focusFeedback(view: View) {
            view.setOnFocusChangeListener { target, focused ->
                target.animate().scaleX(if (focused) 1.03f else 1f)
                    .scaleY(if (focused) 1.03f else 1f)
                    .setDuration(120)
                    .start()
            }
        }

        fun divider(top: Int = 0, bottom: Int = 0): View = View(context).apply {
            setBackgroundColor(Color.argb(20, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
            ).apply {
                topMargin = top
                bottomMargin = bottom
            }
        }

        fun card(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(SURFACE_LOW, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        fun sectionLabel(value: String): TextView =
            label(value.uppercase(), 11f, TEXT_FAINT, medium = true, spacing = 0.14f)

        fun fieldLabel(value: String): TextView =
            label(value, 10f, TEXT_FAINT, medium = true, spacing = 0.12f).apply {
                setPadding(dp(2), 0, 0, dp(4))
            }

        fun textButton(
            value: String,
            color: Int = TEXT_DIM,
            size: Float = 14f,
            onClick: () -> Unit,
        ): TextView = label(value, size, color, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(40)
            background = ripple(rounded(Color.TRANSPARENT, 999))
            isClickable = true
            isFocusable = true
            focusFeedback(this)
            setOnClickListener { onClick() }
        }

        fun action(value: String, primary: Boolean, onClick: () -> Unit): TextView =
            label(value, 14f, if (primary) ON_CONTROL else TEXT, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(
                    if (primary) rounded(CONTROL, 999)
                    else rounded(Color.TRANSPARENT, 999, 1, BORDER),
                )
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(46),
                ).apply { rightMargin = dp(8) }
                focusFeedback(this)
                setOnClickListener { onClick() }
            }

        fun chip(value: String, selected: Boolean, onClick: () -> Unit): TextView =
            label(value, 13f, if (selected) ON_CONTROL else TEXT_DIM, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = ripple(
                    if (selected) rounded(CONTROL, 999)
                    else rounded(Color.TRANSPARENT, 999, 1, BORDER),
                )
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40),
                ).apply { rightMargin = dp(8) }
                focusFeedback(this)
                setOnClickListener { onClick() }
            }

        fun field(value: String, hint: String): EditText = EditText(context).apply {
            setText(value)
            setHint(hint)
            setHintTextColor(TEXT_FAINT)
            setTextColor(TEXT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = inputBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        fun secret(value: String, hint: String): Pair<LinearLayout, EditText> {
            val input = field(value, hint).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            var visible = false
            val toggle = textButton("Show", TEXT_DIM) {}
            toggle.setOnClickListener {
                visible = !visible
                input.inputType = if (visible) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                input.transformationMethod =
                    if (visible) null else PasswordTransformationMethod.getInstance()
                input.typeface = Typeface.MONOSPACE
                input.setSelection(input.text.length)
                toggle.text = if (visible) "Hide" else "Show"
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input)
                addView(toggle)
            }
            return root to input
        }

        fun toggle(initial: Boolean, onChange: (Boolean) -> Unit): View {
            val root = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(30))
                background = ripple(rounded(Color.TRANSPARENT, 999))
                isClickable = true
                isFocusable = true
            }
            val track = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(50), dp(30))
            }
            val thumb = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = dp(4) }
            }
            root.addView(track)
            root.addView(thumb)
            var checked = initial
            fun paint(animate: Boolean) {
                track.background = rounded(if (checked) CONTROL else SURFACE_HIGH, 999)
                thumb.background = circle(if (checked) ON_CONTROL else TEXT_FAINT)
                root.contentDescription = if (checked) "Disable" else "Enable"
                if (animate) {
                    thumb.animate().translationX(if (checked) dp(20).toFloat() else 0f)
                        .setDuration(180)
                        .start()
                } else {
                    thumb.translationX = if (checked) dp(20).toFloat() else 0f
                }
            }
            paint(false)
            focusFeedback(root)
            root.setOnClickListener {
                checked = !checked
                paint(true)
                onChange(checked)
            }
            return root
        }

        fun badge(value: String): TextView = label(value.uppercase(), 9f, TEXT_MUTED, medium = true, spacing = 0.06f).apply {
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = rounded(SURFACE_HIGH, 999)
        }

        fun toggleRow(
            title: String,
            subtitle: String,
            initial: Boolean,
            badges: List<String> = emptyList(),
            onChange: (Boolean) -> Unit,
        ): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(label(title, 15f, TEXT, medium = true))
            if (badges.isNotEmpty()) {
                val badgesRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(5), 0, 0)
                }
                badges.forEach { value ->
                    badgesRow.addView(badge(value), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { rightMargin = dp(5) })
                }
                texts.addView(badgesRow)
            }
            if (subtitle.isNotBlank()) {
                texts.addView(
                    label(subtitle, 12f, TEXT_FAINT).apply {
                        setPadding(0, dp(4), dp(8), 0)
                    },
                )
            }
            row.addView(texts)
            row.addView(toggle(initial, onChange))
            return row
        }

        fun tile(section: Section, subtitle: String? = null, onClick: () -> Unit): LinearLayout {
            val chip = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = rounded(SURFACE_HIGH, 14)
            }
            chip.addView(
                label(section.index, 12f, TEXT_MUTED, medium = true).apply {
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(label(section.title, 16f, TEXT, medium = true))
            texts.addView(
                label(subtitle ?: section.subtitle, 12f, TEXT_FAINT).apply {
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = ripple(rounded(SURFACE_LOW, 18))
                isClickable = true
                isFocusable = true
                addView(chip)
                addView(texts)
                addView(label("›", 22f, TEXT_FAINT))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
                focusFeedback(this)
                setOnClickListener { onClick() }
            }
        }

        fun pageScroll(content: LinearLayout): ScrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun openUrl(url: String) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { toast("No browser available") }
        }

        fun toast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
