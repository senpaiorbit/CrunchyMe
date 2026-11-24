package com.crunchyme

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SettingsUi {

    fun open(context: Context) {
        Settings.init(context)

        val dialog = try {
            Dialog(context, android.R.style.Theme_Material_Dialog)
        } catch (t: Throwable) {
            toast(context, "Settings unavailable in this context")
            return
        }

        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        dialog.setContentView(scroll)

        fun text(value: String, size: Float = 14f, bold: Boolean = false, top: Int = 8): TextView {
            val view = TextView(context)
            view.text = value
            view.textSize = size
            view.setPadding(0, dp(context, top), 0, dp(context, 4))
            if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            return view
        }

        fun section(value: String): TextView = text(value, 17f, true, 18)

        fun edit(hint: String, value: String = "", password: Boolean = false): EditText {
            val view = EditText(context)
            view.hint = hint
            view.setText(value)
            view.setSingleLine(true)
            if (password) {
                view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            view.setPadding(0, dp(context, 4), 0, dp(context, 4))
            return view
        }

        fun button(label: String, action: () -> Unit): Button {
            val view = Button(context)
            view.text = label
            view.setOnClickListener { action() }
            return view
        }

        fun row(vararg views: View): LinearLayout {
            val layout = LinearLayout(context)
            layout.orientation = LinearLayout.HORIZONTAL
            views.forEach { view ->
                layout.addView(
                    view,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            return layout
        }

        fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
            val view = CheckBox(context)
            view.text = label
            view.isChecked = checked
            view.setOnCheckedChangeListener { _, value -> onChange(value) }
            return view
        }

        fun openUrl(url: String) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { toast(context, "No browser available") }
        }

        lateinit var render: () -> Unit

        render = {
            root.removeAllViews()

            root.addView(text("CrunchyMe", 22f, true, 0))
            root.addView(text("TMDB metadata · multi-source stream farm", 13f, false, 0))

            root.addView(section("TMDB API key"))
            val currentKey = Settings.tmdbKey()
            root.addView(
                text(
                    if (currentKey == null) {
                        "No key saved. Search and catalog stay empty until you add one."
                    } else {
                        "Saved: ${currentKey.take(4)}…${currentKey.takeLast(4)}"
                    },
                    13f,
                ),
            )
            val keyInput = edit("Paste your 32-character v3 API key", "", password = true)
            root.addView(keyInput)
            root.addView(
                row(
                    button("Save") {
                        val value = keyInput.text.toString()
                        if (!Settings.isValidTmdbKey(value)) {
                            toast(context, "That does not look like a TMDB v3 key")
                        } else {
                            Settings.saveTmdbKey(value)
                            Tmdb.clearCache()
                            toast(context, "Key saved")
                            render()
                        }
                    },
                    button("Test") {
                        val value = keyInput.text.toString().ifBlank { Settings.tmdbKey().orEmpty() }
                        if (value.isBlank()) {
                            toast(context, "Enter a key first")
                        } else {
                            toast(context, "Testing…")
                            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                val ok = Tmdb.testKey(value)
                                withContext(Dispatchers.Main) {
                                    toast(
                                        context,
                                        if (ok) "Key works" else "Key rejected or network unavailable",
                                    )
                                }
                            }
                        }
                    },
                    button("Get key") { openUrl(Settings.TMDB_LINK) },
                ),
            )

            root.addView(section("Minimum quality"))
            root.addView(text("Current: ${floorLabel(Settings.qualityFloor())}", 13f))
            root.addView(
                row(
                    button("480p") { Settings.saveQualityFloor(480); render() },
                    button("720p") { Settings.saveQualityFloor(720); render() },
                    button("1080p") { Settings.saveQualityFloor(1080); render() },
                    button("Any") { Settings.saveQualityFloor(0); render() },
                ),
            )

            root.addView(section("Sources"))
            root.addView(
                check("Internet Archive (public-domain films)", Settings.archiveEnabled()) {
                    Settings.saveArchiveEnabled(it)
                },
            )
            root.addView(
                check("Stremio / Nuvio addons", Settings.stremioEnabled()) {
                    Settings.saveStremioEnabled(it)
                },
            )

            root.addView(section("Stremio / Nuvio addons"))
            val addons = Settings.addonUrls()
            if (addons.isEmpty()) {
                root.addView(text("No addons yet.", 13f))
            } else {
                addons.forEach { url ->
                    root.addView(
                        row(
                            text(url, 13f, top = 10),
                            button("Remove") {
                                Settings.removeAddon(url)
                                StremioSource.clearCache()
                                render()
                            },
                        ),
                    )
                }
            }
            val addonInput = edit("https://addon.example")
            root.addView(addonInput)
            root.addView(
                button("Add addon") {
                    val added = Settings.addAddon(addonInput.text.toString())
                    if (added == null) {
                        toast(context, "Invalid or duplicate addon URL")
                    } else {
                        toast(context, "Addon added")
                        render()
                    }
                },
            )

            root.addView(section("Custom providers"))
            root.addView(
                text(
                    "Tokens: {tmdb} {imdb} {type} {season} {episode} {title} {year}",
                    12f,
                ),
            )
            val providers = Settings.providers()
            if (providers.isEmpty()) {
                root.addView(text("No custom providers.", 13f))
            } else {
                providers.forEach { provider ->
                    val label = if (provider.json) "${provider.name} (JSON)" else provider.name
                    root.addView(
                        row(
                            check(label, provider.enabled) { on ->
                                Settings.setProviderEnabled(provider.name, on)
                            },
                            button("Remove") {
                                Settings.removeProvider(provider.name)
                                render()
                            },
                        ),
                    )
                }
            }
            val providerName = edit("Name")
            val movieTemplate = edit("Movie URL, e.g. https://host/embed/{tmdb}")
            val tvTemplate = edit("TV URL, e.g. https://host/embed/{tmdb}/{season}/{episode}")
            val referer = edit("Referer (optional)")
            val isJson = check("URL returns JSON", false) {}
            root.addView(providerName)
            root.addView(movieTemplate)
            root.addView(tvTemplate)
            root.addView(referer)
            root.addView(isJson)
            root.addView(
                button("Add provider") {
                    val name = providerName.text.toString().trim()
                    val movie = movieTemplate.text.toString().trim()
                    if (name.isEmpty() || movie.isEmpty()) {
                        toast(context, "Name and movie URL are required")
                    } else if (Settings.providers().any { it.name.equals(name, true) }) {
                        toast(context, "A provider with that name already exists")
                    } else {
                        Settings.saveProviders(
                            Settings.providers() + ProviderTemplate(
                                name = name,
                                movieUrl = movie,
                                tvUrl = tvTemplate.text.toString().trim(),
                                referer = referer.text.toString().trim().ifBlank { null },
                                json = isJson.isChecked,
                            ),
                        )
                        toast(context, "Provider added")
                        render()
                    }
                },
            )

            root.addView(section("Maintenance"))
            root.addView(
                row(
                    button("Clear caches") {
                        LinkCache.clear()
                        Tmdb.clearCache()
                        StremioSource.clearCache()
                        toast(context, "Caches cleared")
                    },
                    button("Reset health") {
                        Health.reset()
                        toast(context, "Source health reset")
                    },
                ),
            )

            root.addView(
                button("Close") {
                    dialog.dismiss()
                },
            )
        }

        render()
        runCatching { dialog.show() }.onFailure {
            toast(context, "Settings unavailable in this context")
        }
    }

    private fun floorLabel(floor: Int): String = if (floor <= 0) "Any" else "${floor}p"

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
