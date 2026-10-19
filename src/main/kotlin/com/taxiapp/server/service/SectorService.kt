package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.sector.CreateSectorRequest
import com.taxiapp.server.dto.sector.PointDto
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.sector.SectorPoint
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.SectorRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.utils.GeometryUtils
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class SectorService(
    private val sectorRepository: SectorRepository,
    private val driverRepository: DriverRepository,
    private val orderRepository: TaxiOrderRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) {
    private val CACHE_KEY = "sectors:all"

    // L1 In-Memory кэш готовых сущностей Sector с предсортированными точками (TTL 60 сек)
    // Исключает тысячи вызовов objectMapper.convertValue и аллокаций SectorPoint в секунду
    @Volatile
    private var localSectorsCache: List<Sector> = emptyList()

    @Volatile
    private var localDtosCache: List<SectorDto> = emptyList()

    @Volatile
    private var cacheExpiresAtMs: Long = 0L

    private fun invalidateCache() {
        cacheExpiresAtMs = 0L
        localSectorsCache = emptyList()
        localDtosCache = emptyList()
        try {
            redisTemplate.delete(CACHE_KEY)
        } catch (_: Exception) {}
    }

    @Transactional(readOnly = true)
    fun getCachedSectorEntities(): List<Sector> {
        val now = System.currentTimeMillis()
        val current = localSectorsCache
        if (now < cacheExpiresAtMs && current.isNotEmpty()) {
            return current
        }

        val sectors = sectorRepository.findAll().map { sector ->
            // Гарантируем, что точки инициализированы и отсортированы в памяти
            val sortedPoints = sector.points.sortedBy { it.pointOrder }.toMutableList()
            sector.points = sortedPoints
            sector
        }
        val dtos = sectors.map { mapToDto(it) }

        localSectorsCache = sectors
        localDtosCache = dtos
        cacheExpiresAtMs = now + 60_000L

        try {
            redisTemplate.opsForValue().set(CACHE_KEY, dtos)
        } catch (_: Exception) {}

        return sectors
    }

    @Transactional(readOnly = true)
    fun getCitySectors(): List<Sector> {
        return getCachedSectorEntities().filter { it.isCity }
    }

    @Transactional(readOnly = true)
    fun getAllSectors(): List<SectorDto> {
        val now = System.currentTimeMillis()
        val currentDtos = localDtosCache
        if (now < cacheExpiresAtMs && currentDtos.isNotEmpty()) {
            return currentDtos
        }

        getCachedSectorEntities()
        return localDtosCache
    }

    /**
     * Сверхбыстрый поиск сектора по координатам в памяти L1 без аллокации временных объектов и без запросов к БД.
     */
    @Transactional(readOnly = true)
    fun findSectorByCoordinates(lat: Double, lng: Double): Sector? {
        val allSectors = getCachedSectorEntities()

        return allSectors.find { sector ->
            sector.points.size >= 3 && GeometryUtils.isPointInPolygon(lat, lng, sector.points)
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

        invalidateCache()
        return mapToDto(saved)
    }

    @Transactional
    fun deleteSector(id: Long) {
        val sector = sectorRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не знайдено") }

        orderRepository.clearSectorReference(id)

        val driversWithSector = driverRepository.findAllByHomeSectorsId(id)
        for (driver in driversWithSector) {
            driver.homeSectors.removeIf { it.id == id }
            driverRepository.save(driver)
        }

        sectorRepository.delete(sector)
        invalidateCache()
    }

    @Transactional
    fun updateSectorName(id: Long, newName: String): SectorDto {
        val sector = sectorRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не знайдено") }

        sector.name = newName
        val saved = sectorRepository.save(sector)

        invalidateCache()
        return mapToDto(saved)
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