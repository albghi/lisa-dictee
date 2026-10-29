package com.primecircle.lisadictee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Service de premier plan « Lisa écoute » : Android l'exige pour utiliser le micro depuis la bulle. */
class MicService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("ecoute", "Lisa écoute", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ecoute").setContentTitle("Lisa t'écoute").setContentText("Touche ✓ pour écrire, ✗ pour annuler")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(7, n)
        return START_NOT_STICKY
    }
}
