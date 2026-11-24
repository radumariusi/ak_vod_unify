package ro.ak.vodscrobbler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
 * peste celelalte aplicații (dacă i-ai dat voie să „apară peste alte aplicații”). Implicit:
 *  - verde: activ, nimic nu rulează;
 *  - roșu: urmărește acum o redare;
 *  - gri: ascultătorul nu are încă acces.
 * Culorile, mărimea, colțul și transparența se schimbă din ecranul AK Scrobbler (vezi [Prefs]).
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var dot: View? = null

    /** Mărimea, colțul și transparența cu care a fost pusă bulina; altele = o refacem. */
    private var dotLayout: Triple<Int, Prefs.Corner, Int>? = null

    private val updateIndicator = Runnable { updateDot() }
    private val statusChanged = { scheduleIndicatorUpdate() }
    private val dotPreferences = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scheduleIndicatorUpdate()
    }

    private fun scheduleIndicatorUpdate() {
        handler.removeCallbacks(updateIndicator)
        handler.post(updateIndicator)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.observe(this, dotPreferences)
        ScrobblerState.observeStatus(statusChanged)
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
        ScrobblerState.keepAliveRunning = true
        scheduleIndicatorUpdate()
        return START_STICKY
    }

    override fun onDestroy() {
        Prefs.stopObserving(this, dotPreferences)
        ScrobblerState.stopObservingStatus(statusChanged)
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
            Intent(this, StatusActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
        val layout = Triple(Prefs.dotSizeDp(this), Prefs.dotCorner(this), Prefs.dotAlpha(this))
        if (dot != null && layout != dotLayout) removeDot()
        val view = dot ?: addDot(layout) ?: return
        val state = when {
            !ScrobblerState.listenerConnected -> Prefs.DotState.OFF
            ScrobblerState.playingApp != null -> Prefs.DotState.PLAYING
            else -> Prefs.DotState.IDLE
        }
        (view.background as? GradientDrawable)?.setColor(Prefs.dotColor(this, state).value)
    }

    private fun addDot(layout: Triple<Int, Prefs.Corner, Int>): View? = try {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (layout.first * resources.displayMetrics.density).toInt()
        val margin = (10 * resources.displayMetrics.density).toInt()
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(46, 204, 113))
                setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.argb(160, 0, 0, 0))
            }
            alpha = layout.third / 100f
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
            gravity = when (layout.second) {
                Prefs.Corner.TOP_END -> Gravity.TOP or Gravity.END
                Prefs.Corner.TOP_START -> Gravity.TOP or Gravity.START
                Prefs.Corner.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
                Prefs.Corner.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
            }
            x = margin
            y = margin
        }
        wm.addView(view, params)
        dot = view
        dotLayout = layout
        view
    } catch (e: Exception) {
        Log.w(TAG, "Nu pot afișa bulina", e)
        null
    }

    private fun removeDot() {
        val view = dot ?: return
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        dot = null
        dotLayout = null
    }

    companion object {
        private const val TAG = "AKScrobbler"
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 1

        fun reconnect(context: Context) {
            start(context)
            MediaListenerService.reconnect(context)
            Forwarder.get(context).flushPending()
        }

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
