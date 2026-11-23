package ro.ak.vodimport

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15 (aplicațiile cu targetSdk 35) desenează ecranul și sub bara de sus și sub cea de jos.
 * Pe telefon, butoanele de sus ajungeau sub ceas / cameră. Împingem conținutul în zona liberă
 * (și deasupra tastaturii). Pe Android mai vechi sistemul face asta singur.
 */
fun View.keepClearOfSystemBars() {
    if (Build.VERSION.SDK_INT < 35) return
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(base[0] + i.left, base[1] + i.top, base[2] + i.right, base[3] + i.bottom)
        insets
    }
    requestApplyInsets()
}
