package com.taxiapp.server.utils

import com.taxiapp.server.model.sector.SectorPoint
import kotlin.math.*

object GeometryUtils {

    // Проверка: находится ли точка внутри полигона
    fun isPointInPolygon(lat: Double, lng: Double, polygon: List<SectorPoint>): Boolean {
        var intersectCount = 0
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            if (((polygon[i].lng > lng) != (polygon[j].lng > lng)) &&
                (lat < (polygon[j].lat - polygon[i].lat) * (lng - polygon[i].lng) / 
                        (polygon[j].lng - polygon[i].lng) + polygon[i].lat)
            ) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }

    // Дистанция между двумя точками (Гаверсинус) в км
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Радиус Земли
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}