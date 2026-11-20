package ro.ak.vodscrobbler

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/**
 * După pornirea TV-ului (sau după o actualizare) cerem sistemului să lege din nou ascultătorul.
 * De obicei Android o face singur; asta e doar o plasă de siguranță pentru unele televizoare.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Access.isListenerEnabled(context)) {
                    NotificationListenerService.requestRebind(ComponentName(context, MediaListenerService::class.java))
                }
                Forwarder.get(context).flushPending()
                KeepAliveService.start(context)
            }
        }
    }
}
