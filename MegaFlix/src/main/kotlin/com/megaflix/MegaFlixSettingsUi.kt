@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.megaflix

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG = Color.parseColor("#07070A")
private val SURFACE = Color.parseColor("#111118")
private val SURFACE_HIGH = Color.parseColor("#1B1B25")
private val BORDER = Color.parseColor("#2C2C3A")
private val TEXT = Color.parseColor("#F5F5F7")
private val TEXT_DIM = Color.parseColor("#A1A1AA")
private val TEXT_FAINT = Color.parseColor("#71717A")
private val ACCENT = Color.parseColor("#8B5CF6")
private val ACCENT_SOFT = Color.parseColor("#2A1F4A")
private val CYAN = Color.parseColor("#22D3EE")
private val DANGER = Color.parseColor("#FB7185")

object MegaFlixSettingsUi {
    private enum class Section(val title: String, val subtitle: String, val index: String) {
        SEARCH("Search", "Depth, timeouts, source aggregation", "01"),
        PROVIDERS("Providers", "Choose which installed extensions MegaFlix can call", "02"),
        HOME("Home screen", "Provider rows and source-count labels", "03"),
        CATALOG("Repository catalog", "Install every CloudStream repository", "04"),
        MASS("Mass install", "Install every provider from every repository", "05"),
        ABOUT("About & diagnostics", "Health, limits and how it works", "06"),
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
        } catch (_: Throwable) {
            ui.toast("Settings unavailable in this context")
            return
        }
        val root = FrameLayout(context).apply { setBackgroundColor(BG) }
        var renderHome: () -> Unit = {}
        var renderDetail: (Section) -> Unit = {}
        var atHome = true

        renderHome = {
            atHome = true
            root.removeAllViews()
            val page = ui.page()
            page.addView(ui.topBar("MEGAFLIX") { dialog.dismiss() })
            page.addView(ui.label("One search. Every source.", 30f, TEXT, medium = true).apply {
                setPadding(0, ui.dp(20), 0, ui.dp(8))
            })
            page.addView(ui.label("A control room for the providers already installed in CloudStream.", 14f, TEXT_DIM))
            page.addView(ui.divider(top = ui.dp(22), bottom = ui.dp(18)))

            val providers = ProviderRegistry.selectable()
            val known = providers.map { it.name }.toSet()
            val enabled = Settings.enabledCount(known)
            page.addView(
                ui.stats(
                    "PROVIDERS" to providers.size.toString(),
                    "ENABLED" to enabled.toString(),
                    "MODE" to Settings.scope().label.uppercase(),
                ),
            )
            page.addView(ui.divider(top = ui.dp(18), bottom = ui.dp(18)))

            page.addView(ui.tile(Section.SEARCH) { renderDetail(Section.SEARCH) })
            page.addView(ui.tile(Section.PROVIDERS, "$enabled of ${providers.size} enabled") {
                renderDetail(Section.PROVIDERS)
            })
            page.addView(ui.tile(Section.HOME, "${Settings.homeRows()} provider rows") {
                renderDetail(Section.HOME)
            })
            page.addView(ui.tile(Section.CATALOG, "Add every CloudStream repository") {
                renderDetail(Section.CATALOG)
            })
            page.addView(ui.tile(Section.MASS, "All providers, one install") {
                renderDetail(Section.MASS)
            })
            page.addView(ui.tile(Section.ABOUT) { renderDetail(Section.ABOUT) })

            page.addView(
                ui.label(
                    "MegaFlix does not copy provider code. It discovers installed extensions at runtime, so provider updates stay independent.",
                    11f,
                    TEXT_FAINT,
                ).apply { setPadding(0, ui.dp(18), 0, 0) },
            )
            root.addView(ui.scroll(page))
        }

        renderDetail = { section ->
            atHome = false
            root.removeAllViews()
            val page = ui.page()
            page.addView(ui.topBar("MEGAFLIX", showBack = true, onBack = { renderHome() }) { dialog.dismiss() })
            page.addView(ui.label(section.title, 26f, TEXT, medium = true).apply {
                setPadding(0, ui.dp(16), 0, ui.dp(4))
            })
            page.addView(ui.label(section.subtitle, 13f, TEXT_DIM))
            page.addView(ui.divider(top = ui.dp(18), bottom = ui.dp(18)))
            when (section) {
                Section.SEARCH -> renderSearch(page, ui, renderDetail)
                Section.PROVIDERS -> renderProviders(page, ui) { renderDetail(Section.PROVIDERS) }
                Section.HOME -> renderHomeSettings(page, ui) { renderDetail(Section.HOME) }
                Section.CATALOG -> renderCatalog(page, ui)
                Section.MASS -> renderMass(page, ui, context.findActivity())
                Section.ABOUT -> renderAbout(page, ui)
            }
            root.addView(ui.scroll(page))
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        dialog.onBack = {
            if (atHome) {
                dialog.dismiss()
            } else {
                renderHome()
            }
            true
        }
        renderHome()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun renderSearch(
        page: LinearLayout,
        ui: Ui,
        renderDetail: (Section) -> Unit,
    ) {
        page.addView(ui.sectionLabel("SEARCH DEPTH"))
        val scopeRow = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(8), 0, ui.dp(10))
        }
        SearchScope.entries.forEach { scope ->
            scopeRow.addView(
                ui.pill(scope.label, Settings.scope() == scope) {
                    Settings.saveScope(scope)
                    renderDetail(Section.SEARCH)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = ui.dp(6)
                },
            )
        }
        page.addView(scopeRow)
        page.addView(ui.label(Settings.scope().description, 12f, TEXT_FAINT).apply {
            setPadding(0, 0, 0, ui.dp(18))
        })

        page.addView(ui.sectionLabel("BEHAVIOUR"))
        page.addView(ui.toggleRow(
            "Aggregate playable links",
            "When a title is opened, ask every matched provider for streams and merge them.",
            Settings.aggregateLinks(),
        ) { Settings.saveAggregateLinks(it) })
        page.addView(ui.toggleRow(
            "Show source count",
            "Append “· N sources” to result titles when more than one provider matches.",
            Settings.showSourceCount(),
        ) { Settings.saveShowSourceCount(it) })
        page.addView(ui.toggleRow(
            "Include catalog-only providers",
            "Show metadata providers such as TMDB and Trakt in the search pool.",
            Settings.includeCatalogProviders(),
        ) { Settings.saveIncludeCatalogProviders(it) })
        page.addView(ui.toggleRow(
            "Include adult results",
            "Keep NSFW results from providers that expose them.",
            Settings.includeAdult(),
        ) { Settings.saveIncludeAdult(it) })

        page.addView(ui.sectionLabel("LIMITS").apply { setPadding(0, ui.dp(22), 0, ui.dp(8)) })
        page.addView(ui.stepper(
            "Provider limit",
            "${Settings.maxProviders()}",
            "Maximum providers contacted per search.",
            onMinus = { Settings.saveMaxProviders(Settings.maxProviders() - 5) },
            onPlus = { Settings.saveMaxProviders(Settings.maxProviders() + 5) },
        ) { renderDetail(Section.SEARCH) })
        page.addView(ui.stepper(
            "Per-provider timeout",
            "${Settings.timeoutMs() / 1000}s",
            "A slow provider is skipped instead of blocking the whole search.",
            onMinus = { Settings.saveTimeoutMs(Settings.timeoutMs() - 1_000) },
            onPlus = { Settings.saveTimeoutMs(Settings.timeoutMs() + 1_000) },
        ) { renderDetail(Section.SEARCH) })
        page.addView(ui.stepper(
            "Parallel requests",
            "${Settings.concurrency()}",
            "How many providers can be contacted at once.",
            onMinus = { Settings.saveConcurrency(Settings.concurrency() - 1) },
            onPlus = { Settings.saveConcurrency(Settings.concurrency() + 1) },
        ) { renderDetail(Section.SEARCH) })
        page.addView(ui.stepper(
            "Sources per title",
            "${Settings.maxLoadProviders()}",
            "Maximum provider versions merged into one result page.",
            onMinus = { Settings.saveMaxLoadProviders(Settings.maxLoadProviders() - 1) },
            onPlus = { Settings.saveMaxLoadProviders(Settings.maxLoadProviders() + 1) },
        ) { renderDetail(Section.SEARCH) })
    }

    private fun renderProviders(page: LinearLayout, ui: Ui, rerender: () -> Unit) {
        val providers = ProviderRegistry.selectable()
        val known = providers.map { it.name }.toSet()
        val enabled = Settings.enabledCount(known)
        page.addView(ui.label("$enabled of ${providers.size} providers enabled", 13f, TEXT_DIM))
        val actions = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(12), 0, ui.dp(8))
        }
        actions.addView(ui.button("Enable all") {
            Settings.setAllProviders(true, known)
            rerender()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(8)
        })
        actions.addView(ui.button("Disable all") {
            Settings.setAllProviders(false, known)
            rerender()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(actions)

        if (providers.isEmpty()) {
            page.addView(ui.label(
                "No providers loaded yet. Open Repository catalog and install the CloudStream repositories, then refresh Extensions in CloudStream.",
                13f,
                TEXT_DIM,
            ).apply { setPadding(0, ui.dp(20), 0, 0) })
            return
        }

        val filter = EditText(ui.context).apply {
            hint = "Filter providers"
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(TEXT_FAINT)
            inputType = InputType.TYPE_CLASS_TEXT
            background = ui.rounded(SURFACE_HIGH, 14, 1, BORDER)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        }
        page.addView(filter, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(10) })

        val list = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        fun renderList(query: String) {
            list.removeAllViews()
            val visible = providers.filter {
                query.isBlank() ||
                    it.name.contains(query, true) ||
                    it.mainUrl.contains(query, true) ||
                    it.lang.contains(query, true)
            }
            if (visible.isEmpty()) {
                list.addView(ui.label("No matching providers.", 13f, TEXT_FAINT))
            }
            visible.forEach { provider ->
                list.addView(ui.providerRow(
                    name = provider.name,
                    subtitle = provider.mainUrl.ifBlank { provider.lang },
                    badge = provider.lang.uppercase(),
                    checked = Settings.providerEnabled(provider.name),
                ) { checked ->
                    Settings.setProviderEnabled(provider.name, checked, known)
                })
            }
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderList("")
    }

    private fun renderHomeSettings(page: LinearLayout, ui: Ui, rerender: () -> Unit) {
        page.addView(ui.label(
            "Home rows are built from installed providers. Each row is delegated to the provider that owns it, so the cards and artwork stay native.",
            13f,
            TEXT_DIM,
        ))
        page.addView(ui.stepper(
            "Provider rows",
            "${Settings.homeRows()}",
            "0 hides provider rows; 6 is a good balance for large installs.",
            onMinus = { Settings.saveHomeRows(Settings.homeRows() - 1) },
            onPlus = { Settings.saveHomeRows(Settings.homeRows() + 1) },
        ) { rerender() })
        page.addView(ui.toggleRow(
            "Auto-install repositories",
            "On plugin load, add missing repositories from the CloudStream catalog.",
            Settings.autoInstallRepos(),
        ) { Settings.saveAutoInstallRepos(it) })
        page.addView(ui.label(
            "Home rows have their own limit. Search always uses the provider pool selected under Providers.",
            12f,
            TEXT_FAINT,
        ).apply { setPadding(0, ui.dp(14), 0, 0) })
    }

    private fun renderCatalog(page: LinearLayout, ui: Ui) {
        page.addView(ui.label(
            "The official CloudStream catalog lists community repositories. Installing them makes their extensions available to MegaFlix.",
            13f,
            TEXT_DIM,
        ))
        val status = ui.label("Checking installed repositories…", 12f, TEXT_FAINT).apply {
            setPadding(0, ui.dp(14), 0, ui.dp(8))
        }
        page.addView(status)
        val button = ui.button("Install all repositories", accent = true) {}
        page.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(8) })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            val installed = runCatching { RepositoryCatalog.installedUrls().size }.getOrDefault(0)
            status.text = "$installed repositories already installed on this device."
        }
        button.setOnClickListener {
            button.isEnabled = false
            button.alpha = 0.6f
            status.text = "Fetching repository catalog…"
            scope.launch {
                val report = runCatching {
                    RepositoryCatalog.installAll { done, total, label ->
                        scope.launch {
                            status.text = if (label == "Done") {
                                "Finishing…"
                            } else {
                                "Installing $done/$total · ${label.substringAfterLast('/')}"
                            }
                        }
                    }
                }.getOrNull()
                button.isEnabled = true
                button.alpha = 1f
                status.text = when {
                    report == null -> "Could not reach the repository catalog. Check your connection and try again."
                    report.failed == 0 -> "Done. ${report.summary()}. Refresh Extensions in CloudStream."
                    else -> "${report.summary()}. Refresh Extensions in CloudStream and retry the failures."
                }
            }
        }
        page.addView(ui.divider(top = ui.dp(22), bottom = ui.dp(12)))
        page.addView(ui.label(
            "After installing repositories, open CloudStream → Settings → Extensions and refresh. MegaFlix will discover the new providers automatically.",
            12f,
            TEXT_FAINT,
        ))
        page.addView(ui.button("Open CloudStream catalog") {
            ui.openUrl("https://cloudstream.miraheze.org/wiki/List_of_extensions")
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(12) })
    }

    private fun renderMass(page: LinearLayout, ui: Ui, activity: Activity?) {
        page.addView(ui.label(
            "This uses CloudStream’s own extension installer to download every working provider from the official repository catalog. MegaFlix then discovers and aggregates them after CloudStream restarts.",
            13f,
            TEXT_DIM,
        ))
        val status = ui.label("Reading repositories…", 12f, TEXT_FAINT).apply {
            setPadding(0, ui.dp(14), 0, ui.dp(10))
        }
        page.addView(status)
        val install = ui.button("Install all providers", accent = true) {}
        install.isEnabled = false
        install.alpha = 0.55f
        page.addView(install, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val refresh = ui.button("Rescan catalog") {}
        page.addView(refresh, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(8) })

        val note = ui.label(
            "700+ extensions is a large install (roughly 140 MB). The first CloudStream start after installing may take a while. Providers that are marked down are skipped.",
            11f,
            TEXT_FAINT,
        ).apply { setPadding(0, ui.dp(14), 0, 0) }
        page.addView(note)

        var catalog: MassCatalog? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun showCatalog(value: MassCatalog?) {
            catalog = value
            if (value == null) {
                status.text = "Could not read the repository catalog. Check your connection and rescan."
                install.isEnabled = false
                install.alpha = 0.55f
                return
            }
            val mb = value.totalBytes / (1024.0 * 1024.0)
            status.text = "${value.repositoryCount} repositories · ${value.plugins.size} providers · ${"%.1f".format(mb)} MB" +
                if (value.skippedDown > 0) " · ${value.skippedDown} marked down skipped" else ""
            install.text = "Install ${value.plugins.size} providers"
            install.isEnabled = activity != null && value.plugins.isNotEmpty()
            install.alpha = if (install.isEnabled) 1f else 0.55f
        }

        fun scan(force: Boolean) {
            install.isEnabled = false
            install.alpha = 0.55f
            status.text = "Reading repositories…"
            scope.launch {
                val value = runCatching { MassInstaller.discover(force) }.getOrNull()
                showCatalog(value)
            }
        }

        install.setOnClickListener {
            val value = catalog ?: return@setOnClickListener
            val target = activity ?: return@setOnClickListener
            install.isEnabled = false
            refresh.isEnabled = false
            install.alpha = 0.55f
            status.text = "Installing 0/${value.plugins.size}…"
            scope.launch {
                val report = runCatching {
                    MassInstaller.installAll(target, value) { done, total, name ->
                        scope.launch { status.text = "Installing $done/$total · ${name.take(40)}" }
                    }
                }.getOrNull()
                install.isEnabled = true
                refresh.isEnabled = true
                install.alpha = 1f
                if (report == null) {
                    status.text = "Install stopped unexpectedly. Rescan and try again."
                } else {
                    ProviderHealth.reset()
                    status.text = report.summary() + ". Restart CloudStream to load the new extensions."
                }
            }
        }

        refresh.setOnClickListener {
            if (activity == null) {
                status.text = "Open MegaFlix settings from CloudStream so the installer has an Activity context."
                return@setOnClickListener
            }
            scan(force = true)
        }

        if (activity == null) {
            status.text = "Open MegaFlix settings from CloudStream so the installer has an Activity context."
        } else {
            scan(force = false)
        }
    }

    private fun renderAbout(page: LinearLayout, ui: Ui) {
        val providers = ProviderRegistry.selectable()
        val known = providers.map { it.name }.toSet()
        page.addView(ui.stats(
            "INSTALLED" to providers.size.toString(),
            "ENABLED" to Settings.enabledCount(known).toString(),
            "FAILURES" to ProviderHealth.snapshot().values.sum().toString(),
        ))
        page.addView(ui.divider(top = ui.dp(18), bottom = ui.dp(16)))
        page.addView(ui.label(
            "MegaFlix is a meta-provider. It does not bundle or redistribute other extensions; it reads CloudStream’s provider registry, calls each provider’s own search/load/link methods, and merges the results.",
            13f,
            TEXT_DIM,
        ))
        page.addView(ui.label(
            "If a provider is blocked by a CAPTCHA, Cloudflare, login, geo-restriction, or DRM, that provider is responsible for its own access. MegaFlix skips failures and keeps the rest of the results.",
            13f,
            TEXT_DIM,
        ).apply { setPadding(0, ui.dp(12), 0, 0) })
        page.addView(ui.button("Reset provider health") {
            ProviderHealth.reset()
            ui.toast("Provider health reset")
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(18) })
        page.addView(ui.button("Open MegaFlix source") {
            ui.openUrl("https://github.com/senpaiorbit/CrunchyMe")
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(10) })
        page.addView(ui.label("MegaFlix 1.0 · CloudStream meta-provider", 11f, TEXT_FAINT).apply {
            setPadding(0, ui.dp(18), 0, 0)
        })
    }

    private class Ui(val context: Context) {
        fun dp(value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        fun page(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(28))
            setBackgroundColor(BG)
        }

        fun scroll(content: LinearLayout): ScrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        fun topBar(
            kicker: String,
            showBack: Boolean = false,
            onBack: (() -> Unit)? = null,
            onClose: () -> Unit,
        ): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (showBack) {
                addView(textButton("‹", TEXT, 30f) { onBack?.invoke() })
                addView(spacer(dp(8)))
            }
            addView(label(kicker, 11f, TEXT_FAINT, medium = true, spacing = 0.18f))
            addView(spacer())
            addView(textButton("×", TEXT_DIM, 24f, onClose))
        }

        fun label(
            text: String,
            size: Float,
            color: Int,
            medium: Boolean = false,
            spacing: Float = 0f,
        ): TextView = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (medium) Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = spacing
            includeFontPadding = false
        }

        fun sectionLabel(text: String): TextView =
            label(text, 11f, TEXT_FAINT, medium = true, spacing = 0.14f).apply {
                setPadding(0, dp(4), 0, dp(6))
            }

        fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = BORDER): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setColor(color)
                if (stroke > 0) setStroke(dp(stroke), strokeColor)
            }

        fun ripple(content: android.graphics.drawable.Drawable): RippleDrawable =
            RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), content, null)

        fun divider(top: Int = 0, bottom: Int = 0): View = View(context).apply {
            setBackgroundColor(BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                topMargin = top
                bottomMargin = bottom
            }
        }

        fun spacer(width: Int = 0): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (width == 0) 0 else width,
                if (width == 0) 0 else 1,
                if (width == 0) 1f else 0f,
            )
        }

        fun tile(section: Section, subtitle: String? = null, onClick: () -> Unit): LinearLayout {
            val chip = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = rounded(SURFACE_HIGH, 14)
            }
            chip.addView(label(section.index, 12f, TEXT_DIM, medium = true).apply {
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(label(section.title, 16f, TEXT, medium = true))
            texts.addView(label(subtitle ?: section.subtitle, 12f, TEXT_FAINT).apply {
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = ripple(rounded(SURFACE, 18, 1, BORDER))
                isClickable = true
                isFocusable = true
                addView(chip)
                addView(texts)
                addView(label("›", 22f, TEXT_FAINT))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
                setOnClickListener { onClick() }
            }
        }

        fun button(text: String, accent: Boolean = false, onClick: () -> Unit): TextView =
            label(text, 14f, if (accent) Color.BLACK else TEXT, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = ripple(rounded(if (accent) ACCENT else SURFACE_HIGH, 14, 1, if (accent) ACCENT else BORDER))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

        fun textButton(text: String, color: Int, size: Float, onClick: () -> Unit): TextView =
            label(text, size, color).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

        fun pill(text: String, selected: Boolean, onClick: () -> Unit): TextView =
            label(text, 12f, if (selected) Color.BLACK else TEXT_DIM, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple(rounded(if (selected) ACCENT else SURFACE_HIGH, 12, 1, if (selected) ACCENT else BORDER))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

        fun stats(vararg values: Pair<String, String>): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                values.forEach { (name, value) ->
                    val cell = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = rounded(SURFACE, 14, 1, BORDER)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(label(name, 9f, TEXT_FAINT, medium = true, spacing = 0.12f))
                        addView(label(value, 18f, TEXT, medium = true).apply { setPadding(0, dp(4), 0, 0) })
                    }
                    addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(8)
                    })
                }
            }

        fun toggleRow(
            title: String,
            subtitle: String,
            initial: Boolean,
            onChange: (Boolean) -> Unit,
        ): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(11), dp(2), dp(11))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(label(title, 14f, TEXT, medium = true))
                addView(label(subtitle, 12f, TEXT_FAINT).apply { setPadding(0, dp(3), dp(10), 0) })
            }
            val toggle = Switch(context).apply {
                isChecked = initial
                showText = false
                buttonTintList = ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            addView(texts)
            addView(toggle)
        }

        fun stepper(
            title: String,
            value: String,
            subtitle: String,
            onMinus: () -> Unit,
            onPlus: () -> Unit,
            rerender: () -> Unit,
        ): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
            addView(label(title, 14f, TEXT, medium = true))
            addView(label(subtitle, 12f, TEXT_FAINT).apply { setPadding(0, dp(3), 0, dp(7)) })
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(textButton("−", TEXT, 24f) {
                    onMinus()
                    rerender()
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(label(value, 15f, CYAN, medium = true).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(textButton("+", TEXT, 24f) {
                    onPlus()
                    rerender()
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
            }
            row.background = rounded(SURFACE, 12, 1, BORDER)
            addView(row)
        }

        fun providerRow(
            name: String,
            subtitle: String,
            badge: String,
            checked: Boolean,
            onChange: (Boolean) -> Unit,
        ): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(SURFACE, 12, 1, BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(label(name, 14f, TEXT, medium = true))
                addView(label(subtitle, 11f, TEXT_FAINT).apply {
                    setPadding(0, dp(3), dp(8), 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            addView(texts)
            addView(label(badge, 9f, CYAN, medium = true, spacing = 0.1f).apply {
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = rounded(ACCENT_SOFT, 6)
            })
            addView(Switch(context).apply {
                isChecked = checked
                showText = false
                buttonTintList = ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            })
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
