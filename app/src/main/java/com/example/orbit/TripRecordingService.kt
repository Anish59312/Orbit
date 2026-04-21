package com.example.orbit

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.orbit.data.DataPointEntity
import com.example.orbit.data.DriveLogDatabase
import com.example.orbit.data.TripEntity
import com.example.orbit.data.TripMetrics
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class TripRecordingService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "trip_recording_channel"
        const val NOTIFICATION_ID = 1
        private val isRunning = AtomicBoolean(false)
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    
    private var rotationVectorSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var database: DriveLogDatabase

    private var currentTripId: Long = -1L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Sensor Data States
    private var currentRotationMatrix = FloatArray(9)
    private var currentLinearAccel = FloatArray(3)
    
    // Low pass filter states
    private var filteredGForceLat = 0f
    private var filteredGForceLon = 0f
    private val alpha = 0.8f // As required: apply a low-pass filter (alpha = 0.8)

    // Latest location
    private var lastLocation: Location? = null
    
    // Notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        
        sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        database = DriveLogDatabase.getDatabase(this)
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trip Recording")
            .setContentText("Waiting for location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { loc ->
                    lastLocation = loc
                    handleNewLocation(loc)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_START) {
            startTrip()
        } else if (action == ACTION_STOP) {
            endTrip()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startTrip() {
        if (!isRunning.compareAndSet(false, true)) return

        startForeground(NOTIFICATION_ID, notificationBuilder.build())

        serviceScope.launch {
            val trip = TripEntity(startTime = System.currentTimeMillis())
            currentTripId = database.tripDao().insertTrip(trip)

            withContext(Dispatchers.Main) {
                registerSensors()
                requestLocationUpdates()
            }
        }
    }

    private fun endTrip() {
        if (!isRunning.compareAndSet(true, false)) return

        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)

        if (currentTripId != -1L) {
            val tripIdToComplete = currentTripId
            currentTripId = -1L
            
            serviceScope.launch {
                val dataPoints = database.tripDao().getDataPointsForTrip(tripIdToComplete)
                val trip = database.tripDao().getTripById(tripIdToComplete)
                
                if (trip != null) {
                    val metrics = computeTripMetrics(dataPoints)
                    val updatedTrip = trip.copy(
                        endTime = System.currentTimeMillis(),
                        metrics = metrics
                    )
                    database.tripDao().updateTrip(updatedTrip)
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val sampleRateHz = sharedPrefs.getInt("sample_rate_hz", 10)
        val intervalMs = (1000L / sampleRateHz)
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
            
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun registerSensors() {
        val sampleRateHz = sharedPrefs.getInt("sample_rate_hz", 10)
        val sensorDelayUs = 1_000_000 / sampleRateHz
        
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, sensorDelayUs)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, sensorDelayUs)
        }
    }

    private fun handleNewLocation(location: Location) {
        val speedKmh = (location.speed * 3.6f)
        
        // Derive pace (min/km) from speed: paceSecPerKm = 3600 / speedKmh.
        // If speedKmh < 3, pace = null (stopped).
        val paceSecPerKm = if (speedKmh >= 3f) {
            3600f / speedKmh
        } else {
            null
        }

        // Apply low pass filter (alpha = 0.8) G = component / 9.81
        // using latest currentLinearAccel mapped to phone coordinates via rotationMatrix
        // Wait, TYPE_ROTATION_VECTOR gives us absolute device orientation.
        // To decompose accelerometer to vehicle coordinates, we need rotation matrix
        val earthAccel = FloatArray(3)
        // This is a simplified decomposition assuming phone is mounted in a specific way,
        // or actually applying rotation matrix to linearAccel
        earthAccel[0] = currentRotationMatrix[0] * currentLinearAccel[0] + currentRotationMatrix[1] * currentLinearAccel[1] + currentRotationMatrix[2] * currentLinearAccel[2]
        earthAccel[1] = currentRotationMatrix[3] * currentLinearAccel[0] + currentRotationMatrix[4] * currentLinearAccel[1] + currentRotationMatrix[5] * currentLinearAccel[2]
        earthAccel[2] = currentRotationMatrix[6] * currentLinearAccel[0] + currentRotationMatrix[7] * currentLinearAccel[1] + currentRotationMatrix[8] * currentLinearAccel[2]
        
        // The problem specification: 
        // gForceLat (left/right, X axis), gForceLon (forward/back, Y axis)
        val rawGForceLat = earthAccel[0] / 9.81f
        val rawGForceLon = earthAccel[1] / 9.81f
        
        filteredGForceLat = alpha * filteredGForceLat + (1 - alpha) * rawGForceLat
        filteredGForceLon = alpha * filteredGForceLon + (1 - alpha) * rawGForceLon

        val dataPoint = DataPointEntity(
            tripId = currentTripId,
            timestamp = System.currentTimeMillis(),
            speedKmh = speedKmh,
            altitudeM = location.altitude,
            gForceLat = filteredGForceLat,
            gForceLon = filteredGForceLon,
            latitude = location.latitude,
            longitude = location.longitude,
            paceSecPerKm = paceSecPerKm
        )

        serviceScope.launch {
            if (currentTripId != -1L) {
                database.tripDao().insertDataPoint(dataPoint)
            }
        }
        
        updateNotification(speedKmh)
    }

    private fun updateNotification(speedKmh: Float) {
        val speedStr = String.format("%.1f km/h", speedKmh)
        notificationBuilder.setContentText("Current Speed: $speedStr")
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                currentLinearAccel[0] = event.values[0]
                currentLinearAccel[1] = event.values[1]
                currentLinearAccel[2] = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun computeTripMetrics(dataPoints: List<DataPointEntity>): TripMetrics {
        if (dataPoints.isEmpty()) {
            return TripMetrics(0f, 0f, 0f, 0L)
        }

        var topSpeed = 0f
        var sumSpeed = 0f
        var distance = 0f
        val startTime = dataPoints.first().timestamp
        val endTime = dataPoints.last().timestamp
        
        val durationSeconds = (endTime - startTime) / 1000L

        for (i in dataPoints.indices) {
            val point = dataPoints[i]
            topSpeed = max(topSpeed, point.speedKmh)
            sumSpeed += point.speedKmh
            
            if (i > 0) {
                val prev = dataPoints[i - 1]
                val loc1 = Location("").apply {
                    latitude = prev.latitude
                    longitude = prev.longitude
                }
                val loc2 = Location("").apply {
                    latitude = point.latitude
                    longitude = point.longitude
                }
                distance += loc1.distanceTo(loc2)
            }
        }

        val avgSpeed = if (dataPoints.isNotEmpty()) sumSpeed / dataPoints.size else 0f
        return TripMetrics(
            topSpeedKmh = topSpeed,
            averageSpeedKmh = avgSpeed,
            distanceMeters = distance,
            durationSeconds = durationSeconds
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trip Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows a notification while recording a trip"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        endTrip()
        serviceScope.cancel()
        super.onDestroy()
    }
}
