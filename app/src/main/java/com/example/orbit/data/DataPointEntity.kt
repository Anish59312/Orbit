package com.example.orbit.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "data_points")
data class DataPointEntity(
    @PrimaryKey(autoGenerate = true) val pointId: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val speedKmh: Float,
    val altitudeM: Double,
    val gForceLat: Float,
    val gForceLon: Float,
    val latitude: Double,
    val longitude: Double,
    val paceSecPerKm: Float?
)
