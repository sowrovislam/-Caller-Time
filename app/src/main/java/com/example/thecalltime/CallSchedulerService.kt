package com.example.thecalltime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class CallSchedulerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "call_scheduler_channel")
            .setContentTitle("Auto Call Service Running")
            .setContentText("Maintaining scheduled calls")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()

    }
}