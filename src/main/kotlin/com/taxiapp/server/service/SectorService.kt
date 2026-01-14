package com.taxiapp.server.service

import com.taxiapp.server.dto.sector.CreateSectorRequest
import com.taxiapp.server.dto.sector.PointDto
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.sector.SectorPoint
import com.taxiapp.server.repository.SectorRepository
import com.taxiapp.server.utils.GeometryUtils // <-- Переконайтеся, що цей імпорт є
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class SectorService(private val sectorRepository: SectorRepository) {

    @Transactional(readOnly = true)
    fun getAllSectors(): List<SectorDto> {
        return sectorRepository.findAll().map { mapToDto(it) }
    }

    // --- НОВИЙ МЕТОД: Пошук сектора за координатами ---
    @Transactional(readOnly = true)
    fun findSectorByCoordinates(lat: Double, lng: Double): Sector? {
        val allSectors = sectorRepository.findAll()
        
        // Перебираємо всі сектори і шукаємо той, в який входить точка
        return allSectors.find { sector ->
            // Використовуємо твій GeometryUtils, який вже є в проекті
            GeometryUtils.isPointInPolygon(lat, lng, sector.points)
        }
    }
    // --------------------------------------------------

    @Transactional
    fun createSector(request: CreateSectorRequest): SectorDto {
        if (request.points.size < 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Сектор повинен мати мінімум 3 точки")
        }

        val sector = Sector(name = request.name)
        
        val points = request.points.mapIndexed { index, p ->
            SectorPoint(
                lat = p.lat,
                lng = p.lng,
                pointOrder = index,
                sector = sector
            )
        }
        
        sector.points.addAll(points)
        val saved = sectorRepository.save(sector)
        return mapToDto(saved)
    }

    @Transactional
    fun deleteSector(id: Long) {
        if (!sectorRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не знайдено")
        }
        sectorRepository.deleteById(id)
    }

    private fun mapToDto(sector: Sector): SectorDto {
        return SectorDto(
            id = sector.id!!,
            name = sector.name,
            points = sector.points.sortedBy { it.pointOrder }.map { PointDto(it.lat, it.lng) }
        )
    }
}