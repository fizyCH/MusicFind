package com.musicfind.app.util

import com.musicfind.app.AppGraph
import com.musicfind.app.R

/**
 * Lightweight accessor for localized strings usable from both Composable and
 * non-Composable code. Android picks the language from the system locale:
 * `res/values` holds English (default) and `res/values-ru` holds Russian.
 */
object Loc {
    fun s(id: Int, vararg args: Any): String {
        val ctx = AppGraph.appContext ?: return ""
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }
}
