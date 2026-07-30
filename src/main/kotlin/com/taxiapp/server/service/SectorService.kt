package com.taxiapp.server.service

import com.taxiapp.server.dto.sector.CreateSectorRequest
import com.taxiapp.server.dto.sector.PointDto
import com.taxiapp.server.dto.sector.SectorDto
import org.springframework.data.redis.core.RedisTemplate
import com.fasterxml.jackson.databind.ObjectMapper
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
    private val orderRepository: TaxiOrderRepository,
    private val redisTemplate: RedisTemplate<String, Any>, // <-- ДОБАВЛЕНО
    private val objectMapper: ObjectMapper // <-- ДОБАВЛЕНО
) {
    private val CACHE_KEY = "sectors:all" // Ключ кэша в Redis

    @Transactional(readOnly = true)
    fun getAllSectors(): List<SectorDto> {
        val cached = redisTemplate.opsForValue().get(CACHE_KEY) as? List<*>
        if (cached != null) {
            return cached.map { objectMapper.convertValue(it, SectorDto::class.java) }
        }

        val sectors = sectorRepository.findAll().map { mapToDto(it) }
        redisTemplate.opsForValue().set(CACHE_KEY, sectors)
        return sectors
    }

    // Пошук сектора за координатами — МЕГА-ОПТИМИЗАЦИЯ С REDIS!
    @Transactional(readOnly = true)
    fun findSectorByCoordinates(lat: Double, lng: Double): Sector? {
        // 1. Берем гео-зоны из быстрого кэша Redis, а не из тяжелой БД
        val allSectorsDtos = getAllSectors()
        
        // 2. Делаем математический просчет полигона прямо в памяти
        val foundSectorDto = allSectorsDtos.find { sectorDto ->
            if (sectorDto.points.size < 3) return@find false
            
            // Воссоздаем временные объекты SectorPoint для совместимости с GeometryUtils
            val tempSectorPoints = sectorDto.points.mapIndexed { index, p ->
                SectorPoint(
                    lat = p.lat, 
                    lng = p.lng, 
                    pointOrder = index, 
                    sector = Sector(name = sectorDto.name, isCity = sectorDto.isCity) // <-- ФИКС: Передаем имя и статус города
                )
            }
            GeometryUtils.isPointInPolygon(lat, lng, tempSectorPoints)
        }

        // 3. Если точка попала в сектор, делаем моментальный поиск ОДНОЙ записи по ID в БД
        return foundSectorDto?.let { 
            sectorRepository.findById(it.id).orElse(null) 
        }
    }

    // Створити сектор — Сбрасываем кэш
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
        
        redisTemplate.delete(CACHE_KEY) // <-- Инвалидация кэша секторов
        return mapToDto(saved)
    }

    // Видалити сектор — Сбрасываем кэш
    @Transactional
    fun deleteSector(id: Long) {
        val sector = sectorRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не знайдено") }

        orderRepository.clearSectorReference(id)

        val driversWithSector = driverRepository.findAllByHomeSectorsId(id)
        for (driver in driversWithSector) {
            // ФИКС: удаляем связь по ID, чтобы обойти баги сравнения Hibernate-прокси объектов
            driver.homeSectors.removeIf { it.id == id }
            driverRepository.save(driver)
        }

        sectorRepository.delete(sector)
        
        redisTemplate.delete(CACHE_KEY) // Инвалидация кэша секторов
    }

    @Transactional
    fun updateSectorName(id: Long, newName: String): SectorDto {
        val sector = sectorRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Сектор не знайдено") }
        
        sector.name = newName
        val saved = sectorRepository.save(sector)
        
        redisTemplate.delete(CACHE_KEY) // Инвалидация кэша секторов, чтобы диспетчерская сразу увидела апдейт
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