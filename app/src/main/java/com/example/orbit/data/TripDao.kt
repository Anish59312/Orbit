package com.example.orbit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE tripId = :tripId")
    suspend fun getTripById(tripId: Long): TripEntity?

    @Insert
    suspend fun insertDataPoint(dataPoint: DataPointEntity)

    @Query("SELECT * FROM data_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getDataPointsForTrip(tripId: Long): List<DataPointEntity>
}
