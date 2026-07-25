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

    @Query("SELECT COUNT(*) FROM tour_content")
    suspend fun contentCount(): Int

    /**
     * Drop stories cached before [cutoffMillis]. A story the guide told months
     * ago is cheaper to fetch again than to keep forever, and a stale one may
     * no longer match what Wikipedia says.
     */
    @Query("DELETE FROM tour_content WHERE created_at < :cutoffMillis")
    suspend fun deleteContentOlderThan(cutoffMillis: Long): Int

    /** Keep the [keep] most recently cached stories and drop the rest. */
    @Query(
        """
        DELETE FROM tour_content WHERE poi_id NOT IN (
            SELECT poi_id FROM tour_content ORDER BY created_at DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM tour_content")
    suspend fun deleteAllContent()
}
