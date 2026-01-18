package com.taxiapp.server.utils

import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.sector.SectorPoint
import kotlin.math.*

object GeometryUtils {

    // Перевірка: чи знаходиться точка всередині полігону
    fun isPointInPolygon(lat: Double, lng: Double, polygon: List<SectorPoint>): Boolean {
        var intersectCount = 0
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val pi = polygon[i]
            val pj = polygon[j]
            
            if (((pi.lng > lng) != (pj.lng > lng)) &&
                (lat < (pj.lat - pi.lat) * (lng - pi.lng) / (pj.lng - pi.lng) + pi.lat)
            ) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }

    // Дистанція між двома точками (Гаверсинус) в метрах (для точності)
    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Радіус Землі в метрах
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
    
    // Старий метод для сумісності (повертає км)
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return calculateDistanceMeters(lat1, lon1, lat2, lon2) / 1000.0
    }

    // --- НОВЕ: Декодування полілінії Google ---
    fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val poly = ArrayList<Pair<Double, Double>>()
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
            val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dLng

            val p = Pair(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }

    // --- НОВЕ: Розрахунок дистанції (Місто vs За містом) ---
    // Повертає Pair(метри_в_місті, метри_за_містом)
    fun calculateRouteSplit(
        polyline: String, 
        citySectors: List<Sector>
    ): Pair<Double, Double> {
        val points = decodePolyline(polyline)
        if (points.isEmpty()) return Pair(0.0, 0.0)

        var distanceCity = 0.0
        var distanceOutCity = 0.0

        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i+1]
            
            val segmentDist = calculateDistanceMeters(start.first, start.second, end.first, end.second)

            // Перевіряємо початок відрізка. Якщо він в "міському" секторі - весь відрізок зараховуємо як місто.
            val isCitySegment = citySectors.any { sector -> 
                isPointInPolygon(start.first, start.second, sector.points)
            }

            if (isCitySegment) {
                distanceCity += segmentDist
            } else {
                distanceOutCity += segmentDist
            }
        }

        return Pair(distanceCity, distanceOutCity)
    }
}