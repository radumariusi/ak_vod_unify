package ro.ak.vodunify.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/** Primește statusul sesiunii PackageInstaller și deschide confirmarea de sistem când e nevoie. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Actualizare instalată")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Instalare eșuată ($status): $msg")
                val message = when {
                    msg?.contains("INSTALL_FAILED_VERIFICATION_FAILURE") == true ->
                        "Android a refuzat instalarea la verificarea de securitate."
                    status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                        "Instalarea a fost blocată de Android sau de politica dispozitivului."
                    else -> "Instalare eșuată: ${msg ?: status}"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "InstallResult"
    }
}
