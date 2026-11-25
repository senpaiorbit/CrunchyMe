package com.megaflix

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class MegaFlix : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(MegaFlixProvider())
        openSettings = { ctx -> MegaFlixSettingsUi.open(ctx) }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { RepositoryCatalog.ensureAllInstalled() }
                .onFailure { Log.w("MegaFlix", "Repository install failed", it) }
        }
    }
}
