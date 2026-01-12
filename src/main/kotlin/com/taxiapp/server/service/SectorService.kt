package com.taxiapp.server.service

import com.taxiapp.server.dto.sector.CreateSectorRequest
import com.taxiapp.server.dto.sector.PointDto
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.sector.SectorPoint
import com.taxiapp.server.repository.SectorRepository
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

    @Transactional
    fun createSector(request: CreateSectorRequest): SectorDto {
        if (request.points.size < 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Сектор должен иметь минимум 3 точки")
        }

        val sector = Sector(name = request.name)
        
        // Преобразуем входящие координаты в сущности SectorPoint
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
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не найден")
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