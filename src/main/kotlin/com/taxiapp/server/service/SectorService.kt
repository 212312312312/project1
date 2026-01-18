package com.taxiapp.server.service

import com.taxiapp.server.dto.sector.CreateSectorRequest
import com.taxiapp.server.dto.sector.PointDto
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.sector.SectorPoint
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.SectorRepository
import com.taxiapp.server.repository.TaxiOrderRepository // <-- ДОДАНО
import com.taxiapp.server.utils.GeometryUtils
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class SectorService(
    private val sectorRepository: SectorRepository,
    private val driverRepository: DriverRepository,
    private val orderRepository: TaxiOrderRepository // <-- ДОДАНО ЗАЛЕЖНІСТЬ
) {

    @Transactional(readOnly = true)
    fun getAllSectors(): List<SectorDto> {
        return sectorRepository.findAll().map { mapToDto(it) }
    }

    @Transactional(readOnly = true)
    fun findSectorByCoordinates(lat: Double, lng: Double): Sector? {
        val allSectors = sectorRepository.findAll()
        
        return allSectors.find { sector ->
            val sortedPoints = sector.points.sortedBy { it.pointOrder }
            if (sortedPoints.size < 3) return@find false
            GeometryUtils.isPointInPolygon(lat, lng, sortedPoints)
        }
    }

    @Transactional
    fun createSector(request: CreateSectorRequest): SectorDto {
        if (request.points.size < 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Сектор повинен мати мінімум 3 точки")
        }

        val sector = Sector(
            name = request.name,
            isCity = request.isCity
        )
        
        val points = request.points.mapIndexed { index, p ->
            SectorPoint(
                lat = p.lat,
                lng = p.lng,
                pointOrder = index,
                sector = sector
            )
        }.toMutableList()
        
        sector.points = points
        val saved = sectorRepository.save(sector)
        return mapToDto(saved)
    }

    @Transactional
    fun deleteSector(id: Long) {
        val sector = sectorRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не знайдено") }

        // 1. Очищаємо посилання в замовленнях (щоб не порушувати FK)
        orderRepository.clearSectorReference(id)

        // 2. Видаляємо сектор у водіїв ("Додому")
        val driversWithSector = driverRepository.findAllByHomeSectorsId(id)
        for (driver in driversWithSector) {
            driver.homeSectors.remove(sector)
            driverRepository.save(driver)
        }

        // 3. Видаляємо сам сектор
        sectorRepository.delete(sector)
    }

    private fun mapToDto(sector: Sector): SectorDto {
        return SectorDto(
            id = sector.id!!,
            name = sector.name,
            isCity = sector.isCity,
            points = sector.points.sortedBy { it.pointOrder }.map { PointDto(it.lat, it.lng) }
        )
    }
}