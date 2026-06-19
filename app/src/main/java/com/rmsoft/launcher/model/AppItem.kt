package com.rmsoft.launcher.model

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    /** Explicit launch intent for internal tiles (e.g. the custom Settings screen). */
    val intent: Intent? = null
)
