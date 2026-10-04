package com.musicfind.app.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the in-app language choice ("en", "ru" or "system") to the
 * application resources. All user-facing text goes through [Loc], which reads
 * the application context, so overriding it here is enough.
 */
object LocaleHelper {
    fun apply(context: Context, tag: String) {
        val app = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = app.getSystemService(LocaleManager::class.java)
            if (manager != null) {
                val list = if (tag == "system") {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(tag)
                }
                manager.applicationLocales = list
            }
        }

        val res = app.resources
        val config = Configuration(res.configuration)
        val locales = if (tag == "system") {
            Configuration(Resources.getSystem().configuration).locales
        } else {
            LocaleList.forLanguageTags(tag)
        }
        if (locales.isEmpty) return
        Locale.setDefault(locales[0])
        config.setLocales(locales)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }
}
