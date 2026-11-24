package com.crunchyme

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CrunchyMe : Plugin() {

    override fun load(context: Context) {
        Settings.init(context)
        Logx.sink = { line -> Log.i("CrunchyMe", line) }
        Http.engine = CloudStreamEngine
        Tmdb.keyProvider = { Settings.tmdbKey() }
        CloudStreamExtractors.install()
        registerMainAPI(CrunchyMeProvider())
        openSettings = { ctx -> SettingsUi.open(ctx) }
    }
}
