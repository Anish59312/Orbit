package com.example.orbit

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TripRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Implementation for location recording will go here
        return START_STICKY
    }
}
