package com.spiritwisestudios.gpstracker.data.db.entity

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PointOfInterestEntityTest {

    private fun poi(isVisited: Boolean = false, visitedDate: Long? = null) = PointOfInterest(
        id = "poi-1",
        name = "Fort Point",
        latLng = LatLng(37.81, -122.47),
        address = "",
        category = "HISTORICAL",
        rating = 4.5,
        placeId = "poi-1",
        isVisited = isVisited,
        visitedDate = visitedDate
    )

    @Test
    fun `visited date round-trips through entity and back`() {
        val entity = PointOfInterestEntity.fromDomainModel(poi(isVisited = true, visitedDate = 1234L))
        assertEquals(1234L, entity.visitedDate)
        assertEquals(1234L, entity.toDomainModel().visitedDate)
    }

    @Test
    fun `re-saving a visited place keeps the original visit timestamp`() {
        // First save stamps a date, later saves must not move it
        val firstSave = PointOfInterestEntity.fromDomainModel(poi(isVisited = true))
        assertNotNull(firstSave.visitedDate)

        val reSave = PointOfInterestEntity.fromDomainModel(firstSave.toDomainModel())
        assertEquals(firstSave.visitedDate, reSave.visitedDate)
    }

    @Test
    fun `first visit gets a timestamp stamped`() {
        val entity = PointOfInterestEntity.fromDomainModel(poi(isVisited = true))
        assertNotNull(entity.visitedDate)
    }

    @Test
    fun `unvisited places carry no visit date`() {
        assertNull(PointOfInterestEntity.fromDomainModel(poi(isVisited = false)).visitedDate)
    }
}
