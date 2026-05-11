package com.taxiapp.server.utils

import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.sector.SectorPoint
import org.slf4j.LoggerFactory
import kotlin.math.*

object GeometryUtils {

    private val logger = LoggerFactory.getLogger(GeometryUtils::class.java)

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

    // Дистанція між двома точками (Гаверсинус) в метрах
    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
    
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return calculateDistanceMeters(lat1, lon1, lat2, lon2) / 1000.0
    }

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

    // --- ОНОВЛЕНО: Розрахунок дистанції з ДЕТАЛЬНИМ ЛОГУВАННЯМ ---
    fun calculateRouteSplit(
        polyline: String, 
        citySectors: List<Sector>
    ): Pair<Double, Double> {
        val points = decodePolyline(polyline)
        if (points.isEmpty()) {
            logger.info("[PRICE_CALC_GEO] Полилайн пуст, возвращаем (0.0, 0.0)")
            return Pair(0.0, 0.0)
        }

        var distanceCity = 0.0
        var distanceOutCity = 0.0

        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i+1]
            
            val segmentDist = calculateDistanceMeters(start.first, start.second, end.first, end.second)

            // Шукаємо, в який САМЕ сектор потрапила точка
            val foundSector = citySectors.find { sector -> 
                isPointInPolygon(start.first, start.second, sector.points)
            }

            if (foundSector != null) {
                distanceCity += segmentDist
            } else {
                distanceOutCity += segmentDist
            }
        }

        logger.info("[PRICE_CALC_GEO] === РАЗБИВКА ПОЛИЛАЙНА ===")
        logger.info("[PRICE_CALC_GEO] Точек в маршруте: ${points.size}")
        logger.info("[PRICE_CALC_GEO] Дистанция внутри полигонов города (метры): $distanceCity")
        logger.info("[PRICE_CALC_GEO] Дистанция за полигонами города (метры): $distanceOutCity")
        logger.info("[PRICE_CALC_GEO] Суммарная дистанция по точкам: ${distanceCity + distanceOutCity}")
        logger.info("[PRICE_CALC_GEO] ===============================")

        return Pair(distanceCity, distanceOutCity)
    }
}