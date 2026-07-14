package dev.kmcp.samples.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that hosts the capability MCP server on 127.0.0.1:8931
 * for as long as the user keeps it running.
 */
class CapabilityService : Service() {

    private var http: LoopbackHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "kmcp capability server", NotificationManager.IMPORTANCE_LOW),
        )
        val gate = AndroidPermissionGate(this)
        val server = CapabilityServer.build(
            gate = gate,
            contacts = AndroidContactsProvider(this),
            calendar = AndroidCalendarProvider(this),
            notifier = Notifier { title, body ->
                manager.notify(
                    NOTIFICATION_ID_TOOL,
                    Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .build(),
                )
            },
        )
        http = LoopbackHttpServer(server).also { it.start() }
        startForeground(
            NOTIFICATION_ID_SERVICE,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("kmcp capability server")
                .setContentText("Serving MCP tools on 127.0.0.1:8931")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build(),
        )
    }

    override fun onDestroy() {
        http?.stop()
        http = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "kmcp-capability-server"
        const val NOTIFICATION_ID_SERVICE = 1
        const val NOTIFICATION_ID_TOOL = 2
    }
}
