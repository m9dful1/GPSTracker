package com.spiritwisestudios.gpstracker.data.db.converters

import androidx.room.TypeConverter
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Converter for LatLng objects to be stored in Room database
 */
class LatLngConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromLatLng(latLng: LatLng?): String? {
        return if (latLng == null) null else gson.toJson(latLng)
    }

    @TypeConverter
    fun toLatLng(latLngString: String?): LatLng? {
        if (latLngString == null) return null
        val type = object : TypeToken<LatLng>() {}.type
        return gson.fromJson(latLngString, type)
    }
} 