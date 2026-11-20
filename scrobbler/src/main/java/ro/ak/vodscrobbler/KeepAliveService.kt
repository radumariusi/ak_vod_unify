package ro.ak.vodscrobbler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Serviciu în prim-plan care ține procesul AK Scrobbler „important” pentru Android, ca
 * televizoarele agresive (ex. TCL) să nu-l închidă. Tot el desenează bulina din dreapta sus,
 * peste celelalte aplicații (dacă i-ai dat voie să „apară peste alte aplicații”):
 *  - verde: activ, nimic nu rulează;
 *  - roșu: urmărește acum o redare;
 *  - gri: ascultătorul nu are încă acces.
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var dot: View? = null

    private val refresher = object : Runnable {
        override fun run() {
            updateDot()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScrobblerState.keepAliveRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // Android 12+ nu permite oricând pornirea din fundal; reîncercăm la următoarea ocazie.
            Log.w(TAG, "Nu pot porni în prim-plan", e)
            ScrobblerState.keepAliveRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(refresher)
        handler.post(refresher)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeDot()
        ScrobblerState.keepAliveRunning = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AK Scrobbler activ", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Ține AK Scrobbler pornit ca să vadă ce rulează pe TV."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, StatusActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scrobbler)
            .setContentTitle("AK Scrobbler e activ")
            .setContentText("Trimite în AK VOD Unify ce vezi pe TV.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateDot() {
        val wanted = Prefs.dotEnabled(this) && Settings.canDrawOverlays(this)
        if (!wanted) {
            removeDot()
            return
        }
        val view = dot ?: addDot() ?: return
        val color = when {
            !ScrobblerState.listenerConnected -> Color.rgb(140, 140, 140)
            ScrobblerState.live.any { it.state == PlayState.PLAYING } -> Color.rgb(229, 9, 20)
            else -> Color.rgb(46, 204, 113)
        }
        (view.background as? GradientDrawable)?.setColor(color)
    }

    private fun addDot(): View? = try {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (12 * resources.displayMetrics.density).toInt()
        val margin = (10 * resources.displayMetrics.density).toInt()
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(46, 204, 113))
                setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.argb(160, 0, 0, 0))
            }
            alpha = 0.85f
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = margin
        }
        wm.addView(view, params)
        dot = view
        view
    } catch (e: Exception) {
        Log.w(TAG, "Nu pot afișa bulina", e)
        null
    }

    private fun removeDot() {
        val view = dot ?: return
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        dot = null
    }

    companion object {
        private const val TAG = "AKScrobbler"
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 1

        /** Pornește serviciul dacă se poate (din fundal, Android 12+ poate refuza: nu e grav). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Pornirea serviciului refuzată acum", e)
            }
        }
    }
}
