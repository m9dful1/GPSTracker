package com.spiritwisestudios.gpstracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spiritwisestudios.gpstracker.data.db.entity.TourContentEntity

@Dao
interface TourContentDao {

    @Query("SELECT * FROM tour_content WHERE poi_id = :poiId")
    suspend fun getContentForPoi(poiId: String): TourContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContent(content: TourContentEntity)

    @Query("DELETE FROM tour_content")
    suspend fun deleteAllContent()
}
