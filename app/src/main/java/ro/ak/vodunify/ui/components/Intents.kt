package ro.ak.vodunify.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Deschide un URL extern; pe TV fără browser afișează un mesaj în loc să crape. */
fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Nu există o aplicație care să deschidă linkul", Toast.LENGTH_LONG).show()
    }
}
