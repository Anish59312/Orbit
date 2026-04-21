package com.example.orbit.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

data class TripMetrics(
    val topSpeedKmh: Float,
    val averageSpeedKmh: Float,
    val distanceMeters: Float,
    val durationSeconds: Long
)

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val tripId: Long = 0,
    val startTime: Long,
    val endTime: Long = 0L,
    @Embedded val metrics: TripMetrics? = null
)
