package app.matterpair.commission

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.matterpair.MatterPairApp
import app.matterpair.R
import app.matterpair.ui.MainActivity
import app.matterpair.ui.phaseLabel
import kotlinx.coroutines.launch

/**
 * Foreground service (type `connectedDevice`) that keeps the process alive while the phone acts
 * as BLE proxy. Aggressive battery managers (as on many custom ROMs, incl. /e/OS) would otherwise
 * kill the app when the screen turns off mid-commissioning.
 *
 * The service holds no logic of its own; it mirrors [CommissioningSession.state] into the
 * notification.
 */
class CommissioningService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(getString(R.string.phase_preparing)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        lifecycleScope.launch {
            (application as MatterPairApp).container.session.state.collect { s ->
                if (s.running) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, build(getString(phaseLabel(s.phase))))
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "commissioning"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CommissioningService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommissioningService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
