package com.taxiapp.server.repository

import com.taxiapp.server.model.sector.Sector
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SectorRepository : JpaRepository<Sector, Long> {

    @Query(value = """
        SELECT s.* FROM sectors s
        WHERE ST_Contains(
            (
                SELECT ST_MakePolygon(ST_AddPoint(line, ST_StartPoint(line)))
                FROM (
                    SELECT ST_MakeLine(ST_MakePoint(sp.lng, sp.lat) ORDER BY sp.point_order) as line
                    FROM sector_points sp
                    WHERE sp.sector_id = s.id
                ) AS subquery
            ),
            ST_MakePoint(:lng, :lat)
        )
    """, nativeQuery = true)
    fun findSectorByCoordinates(@Param("lat") lat: Double, @Param("lng") lng: Double): Sector?
}