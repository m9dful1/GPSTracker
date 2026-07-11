package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng

/**
 * Encoded polyline utilities. The default precision (1E5) matches the classic
 * Google/OSRM polyline5 format; Valhalla encodes shapes with six decimal
 * digits (polyline6), so pass 1E6 for those.
 */
object Polyline {

    const val PRECISION_5 = 1E5
    const val PRECISION_6 = 1E6

    /**
     * Decode an encoded polyline string into a list of LatLng points.
     */
    fun decode(encoded: String, precision: Double = PRECISION_5): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat.toDouble() / precision, lng.toDouble() / precision))
        }

        return poly
    }
}
