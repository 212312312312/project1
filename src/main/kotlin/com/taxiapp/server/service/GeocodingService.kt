package com.taxiapp.server.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.taxiapp.server.dto.order.NearbyPlaceSuggestionDto
import com.taxiapp.server.dto.order.ReverseGeocodeResponseDto
import com.taxiapp.server.repository.TaxiOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Service
class GeocodingService(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val sectorService: SectorService,
    private val orderRepository: TaxiOrderRepository,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(GeocodingService::class.java)

    companion object {
        // Жесткий порог для клиента: предлагать только адреса от 2.0 км (2000 м), куда реально заказывают такси
        private const val MIN_CLIENT_DISTANCE_METERS = 2000

        // С учетом шага сетки (~380 м) отбираем кандидатов на дистанции >= 2200 м от центра сетки
        private const val MIN_GRID_CANDIDATE_DISTANCE_METERS = 2200
        private const val MAX_GRID_CANDIDATE_DISTANCE_METERS = 12000

        // Минимальное расстояние МЕЖДУ самими предлагаемыми точками (900 м),
        // чтобы в выдаче никогда не было 5–6 адресов из одного пятачка в 10–20 метрах друг от друга
        private const val MIN_SEPARATION_BETWEEN_SUGGESTIONS_METERS = 900
    }

    private data class CachedNearbyGridEntry(
        val items: List<NearbyPlaceSuggestionDto>,
        val expiresAtMs: Long
    )

    // Пул потоков для параллельного опроса гео-источников при прогреве холодной ячейки
    private val geoExecutor = Executors.newCachedThreadPool()

    // L1 In-Memory кэш по квадратам сетки (~380x380 м), время жизни 30 минут, отдача за ~0.05 мс
    private val nearbyL1Cache = ConcurrentHashMap<String, CachedNearbyGridEntry>()

    // Single-Flight блокировки по ключу сетки (защита от Cache Stampede / Thundering Herd)
    private val gridLocks = ConcurrentHashMap<String, Any>()

    /**
     * Серверный обратный геокодинг с кэшированием в Redis (точность 5 знаков = ~1.1 м, TTL 24 часа)
     * и безотказным фоллбэком на сектора агрегатора при недоступности внешнего геокодера.
     */
    fun reverseGeocode(lat: Double, lng: Double, lang: String = "uk"): ReverseGeocodeResponseDto {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (abs(lat) < 0.0001 && abs(lng) < 0.0001)) {
            return ReverseGeocodeResponseDto(address = "Точка на карті", source = "fallback")
        }

        val normalizedLang = when (lang.lowercase().trim()) {
            "en" -> "en"
            else -> "uk"
        }

        // Ключ кэша с точностью 5 знаков после запятой (~1.1 метра)
        val cacheKey = String.format(Locale.US, "geo:rev:v1:%s:%.5f:%.5f", normalizedLang, lat, lng)

        try {
            val cached = redisTemplate.opsForValue().get(cacheKey)?.toString()
            if (!cached.isNullOrBlank()) {
                val parts = cached.split("|||", limit = 3)
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    return ReverseGeocodeResponseDto(
                        address = parts[0],
                        rawDisplayName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                        source = parts.getOrNull(2) ?: "redis_cache"
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(">>> [GeocodingService] Ошибка чтения кэша Redis: ${e.message}")
        }

        // 1. Запрос к OpenStreetMap Nominatim по точным координатам без округления
        val nominatimResult = fetchFromNominatim(lat, lng, normalizedLang)
        if (nominatimResult != null && nominatimResult.address.isNotBlank()) {
            try {
                val cacheValue = "${nominatimResult.address}|||${nominatimResult.rawDisplayName ?: ""}|||nominatim"
                redisTemplate.opsForValue().set(cacheKey, cacheValue, 24, TimeUnit.HOURS)
            } catch (e: Exception) {
                logger.warn(">>> [GeocodingService] Ошибка записи в Redis: ${e.message}")
            }
            return nominatimResult
        }

        // 2. Безотказный фоллбэк по полигонам секторов в памяти L1
        val sectorName = try {
            sectorService.findSectorByCoordinates(lat, lng)?.name
        } catch (e: Exception) {
            null
        }

        val fallbackText = if (!sectorName.isNullOrBlank()) {
            sectorName
        } else if (normalizedLang == "en") {
            "Point on map"
        } else {
            "Точка на карті"
        }

        return ReverseGeocodeResponseDto(
            address = fallbackText,
            rawDisplayName = null,
            source = if (sectorName != null) "sector_fallback" else "fallback"
        )
    }

    /**
     * Высокопроизводительная выдача 5–6 РАЗНООБРАЗНЫХ важных точек притяжения
     * (метро, трамвайные/транспортные узлы, ТРЦ, больницы, парки, вокзалы, главные проспекты)
     * на разном удалении (от 2.0 км до 7.5 км) и в разных направлениях от клиента.
     */
    fun getNearbySuggestions(
        lat: Double,
        lng: Double,
        lang: String = "uk",
        limit: Int = 6
    ): List<NearbyPlaceSuggestionDto> {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (abs(lat) < 0.0001 && abs(lng) < 0.0001)) {
            return emptyList()
        }

        val normalizedLang = if (lang.lowercase().trim() == "en") "en" else "uk"
        val safeLimit = limit.coerceIn(1, 10)

        // Квантование координат в сетку ~380x380 метров (v5 — пространственно разнесенные точки по категориям)
        val gridStep = 0.0035
        val gridLat = round(lat / gridStep) * gridStep
        val gridLng = round(lng / gridStep) * gridStep
        val cacheKey = String.format(Locale.US, "geo:nearby:v5:%s:%.4f:%.4f", normalizedLang, gridLat, gridLng)

        val now = System.currentTimeMillis()

        // 1. Проверка сверхбыстрого L1 кэша в оперативной памяти
        val l1Hit = nearbyL1Cache[cacheKey]
        if (l1Hit != null && now < l1Hit.expiresAtMs && l1Hit.items.size >= safeLimit) {
            val sliced = personalizeAndDiversify(l1Hit.items, lat, lng, safeLimit)
            if (sliced.size >= 4) return sliced
        }

        // 2. Проверка L2 кэша в Redis
        val l2Items = readNearbyFromRedis(cacheKey)
        if (l2Items != null && l2Items.size >= safeLimit) {
            putToL1Cache(cacheKey, l2Items, now + TimeUnit.MINUTES.toMillis(30))
            val sliced = personalizeAndDiversify(l2Items, lat, lng, safeLimit)
            if (sliced.size >= 4) return sliced
        }

        // 3. Single-Flight прогрев ячейки сетки (только 1 поток идет в БД и параллельные OSM-источники)
        val lock = gridLocks.computeIfAbsent(cacheKey) { Any() }
        val generatedCandidates = synchronized(lock) {
            try {
                val recheckL1 = nearbyL1Cache[cacheKey]
                if (recheckL1 != null && System.currentTimeMillis() < recheckL1.expiresAtMs && recheckL1.items.size >= safeLimit) {
                    return@synchronized recheckL1.items
                }

                val candidates = buildGridCandidatesParallel(gridLat, gridLng, normalizedLang)
                if (candidates.isNotEmpty()) {
                    if (candidates.size >= 5) {
                        putToL1Cache(cacheKey, candidates, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30))
                        writeNearbyToRedis(cacheKey, candidates)
                    } else {
                        putToL1Cache(cacheKey, candidates, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2))
                    }
                }
                candidates
            } finally {
                gridLocks.remove(cacheKey)
            }
        }

        return personalizeAndDiversify(generatedCandidates, lat, lng, safeLimit)
    }

    /**
     * Пересчитывает точное расстояние от клиента и выбирает [limit] точек так, чтобы:
     * 1) Каждая точка была строго >= 2000 м (2.0 км) от клиента.
     * 2) В подборку попали РАЗНЫЕ категории (метро/трамвай/вокзал, ТРЦ/супермаркет, больница, парк/вуз, главные проспекты).
     * 3) Точки находились минимум в 900 метрах друг от друга (никаких скученных адресов из одного пятачка!)
     *    и на разной дистанции от клиента (например: 2.2 км, 2.8 км, 3.5 км, 4.3 км, 5.2 км, 6.4 км).
     */
    private fun personalizeAndDiversify(
        candidates: List<NearbyPlaceSuggestionDto>,
        userLat: Double,
        userLng: Double,
        limit: Int
    ): List<NearbyPlaceSuggestionDto> {
        if (candidates.isEmpty()) return emptyList()

        val valid = candidates
            .map { item ->
                val dist = computeHaversineMeters(userLat, userLng, item.lat, item.lng)
                item.copy(distanceMeters = dist)
            }
            .filter { it.distanceMeters in MIN_CLIENT_DISTANCE_METERS..MAX_GRID_CANDIDATE_DISTANCE_METERS }

        if (valid.isEmpty()) return emptyList()

        val selected = mutableListOf<NearbyPlaceSuggestionDto>()
        val usedCategoriesCount = HashMap<String, Int>()

        fun isFarEnoughFromSelected(cand: NearbyPlaceSuggestionDto, minSepMeters: Int): Boolean {
            return selected.all { chosen ->
                computeHaversineMeters(cand.lat, cand.lng, chosen.lat, chosen.lng) >= minSepMeters
            }
        }

        // Приоритетный порядок категорий для максимального разнообразия в списке:
        // 1. popular (реальные популярные адреса поездок)
        // 2. transport (станция метро, остановка скоростного трамвая, вокзал, автостанция)
        // 3. mall (ТРЦ, крупный супермаркет, рынок)
        // 4. hospital (больница, медцентр)
        // 5. landmark (университет, парк, кинотеатр, площадь)
        // 6. major_street (главный проспект, бульвар, набережная)
        // 7. street (улица в соседнем районе)
        val priorityCategories = listOf(
            "popular",
            "transport",
            "mall",
            "hospital",
            "landmark",
            "major_street",
            "street"
        )

        // Проход 1: Берем по 1 лучшему представителю из каждой категории с жестким разносом >= 900 м друг от друга
        // и желательным шагом по радиусу от клиента (>= 350 м разницы по дальности)
        for (cat in priorityCategories) {
            if (selected.size >= limit) break
            val catCandidates = valid.filter { it.category == cat }.sortedBy { it.distanceMeters }
            val pick = catCandidates.firstOrNull { cand ->
                isFarEnoughFromSelected(cand, MIN_SEPARATION_BETWEEN_SUGGESTIONS_METERS) &&
                        selected.all { abs(it.distanceMeters - cand.distanceMeters) >= 300 }
            } ?: catCandidates.firstOrNull { cand ->
                isFarEnoughFromSelected(cand, MIN_SEPARATION_BETWEEN_SUGGESTIONS_METERS)
            }

            if (pick != null) {
                selected.add(pick)
                usedCategoriesCount[cat] = (usedCategoriesCount[cat] ?: 0) + 1
            }
        }

        // Проход 2: Если еще не набрали 6 элементов — добираем любые оставшиеся точки,
        // которые отстоят от уже выбранных минимум на 900 метров
        if (selected.size < limit) {
            for (cand in valid) {
                if (selected.size >= limit) break
                if (selected.any { it.placeId == cand.placeId }) continue
                val catCount = usedCategoriesCount[cand.category] ?: 0
                if (catCount < 2 && isFarEnoughFromSelected(cand, MIN_SEPARATION_BETWEEN_SUGGESTIONS_METERS)) {
                    selected.add(cand)
                    usedCategoriesCount[cand.category] = catCount + 1
                }
            }
        }

        // Проход 3: Мягкий добор (разнос >= 500 м), если в небольшом городе мало категорий
        if (selected.size < limit) {
            for (cand in valid) {
                if (selected.size >= limit) break
                if (selected.any { it.placeId == cand.placeId }) continue
                if (isFarEnoughFromSelected(cand, 500)) {
                    selected.add(cand)
                }
            }
        }

        // Финальная сортировка по возрастанию километража (например: 2.2 км -> 2.9 км -> 3.6 км -> 4.4 км -> 5.3 км -> 6.5 км)
        return selected.sortedBy { it.distanceMeters }.take(limit)
    }

    private fun putToL1Cache(key: String, items: List<NearbyPlaceSuggestionDto>, expiresAtMs: Long) {
        if (nearbyL1Cache.size > 5000) {
            val now = System.currentTimeMillis()
            val expiredKeys = nearbyL1Cache.entries.filter { it.value.expiresAtMs < now }.map { it.key }
            expiredKeys.forEach { nearbyL1Cache.remove(it) }
            if (nearbyL1Cache.size > 5000) {
                nearbyL1Cache.keys.take(1000).forEach { nearbyL1Cache.remove(it) }
            }
        }
        nearbyL1Cache[key] = CachedNearbyGridEntry(items, expiresAtMs)
    }

    private fun readNearbyFromRedis(cacheKey: String): List<NearbyPlaceSuggestionDto>? {
        return try {
            val raw = redisTemplate.opsForValue().get(cacheKey) ?: return null
            when (raw) {
                is String -> {
                    if (raw.isBlank()) null
                    else objectMapper.readValue(raw, object : TypeReference<List<NearbyPlaceSuggestionDto>>() {})
                }
                else -> {
                    objectMapper.convertValue(raw, object : TypeReference<List<NearbyPlaceSuggestionDto>>() {})
                }
            }
        } catch (e: Exception) {
            logger.debug(">>> [GeocodingService] Ошибка чтения nearby из Redis: ${e.message}")
            null
        }
    }

    private fun writeNearbyToRedis(cacheKey: String, items: List<NearbyPlaceSuggestionDto>) {
        try {
            val jsonStr = objectMapper.writeValueAsString(items)
            redisTemplate.opsForValue().set(cacheKey, jsonStr, 12, TimeUnit.HOURS)
        } catch (e: Exception) {
            logger.debug(">>> [GeocodingService] Ошибка записи nearby в Redis: ${e.message}")
        }
    }

    /**
     * Собирает пространственно распределенный пул из ~18–24 кандидатов вокруг клиента
     * по спирали на разных дистанциях (от 2.3 км до 7.0 км) и в разных направлениях:
     * - Популярные адреса поездок из БД (разнесенные минимум на 900 м друг от друга)
     * - Важные городские узлы из Overpass API (метро, остановки трамвая, вокзалы, ТРЦ, больницы, парки, университеты)
     * - 6 спиральных сателлитных точек Photon на РАЗНЫХ радиусах (2.4 км, 3.0 км, 3.7 км, 4.5 км, 5.4 км, 6.4 км),
     *   причем из каждого сателлита берется максимум 1 лучший POI и 1 главная улица!
     */
    private fun buildGridCandidatesParallel(
        gridLat: Double,
        gridLng: Double,
        lang: String
    ): List<NearbyPlaceSuggestionDto> {
        val pool = mutableListOf<NearbyPlaceSuggestionDto>()
        val seenTitles = HashSet<String>()

        fun addCandidateIfSpread(cand: NearbyPlaceSuggestionDto, minSeparationMeters: Int = 750): Boolean {
            val normKey = normalizeDedupKey(cand.title)
            if (normKey.length < 3 || normKey in seenTitles) return false
            val isFarEnough = pool.all { existing ->
                computeHaversineMeters(cand.lat, cand.lng, existing.lat, existing.lng) >= minSeparationMeters
            }
            if (!isFarEnough) return false
            seenTitles.add(normKey)
            pool.add(cand)
            return true
        }

        val sectorName = try {
            sectorService.findSectorByCoordinates(gridLat, gridLng)?.name
        } catch (_: Exception) {
            null
        }
        val defaultSubtitle = sectorName ?: if (lang == "en") "City" else "Місто"

        // 1. Популярные адреса из локальной БД на дистанции 2.2–9.5 км (берем разнесенные по разным местам)
        try {
            val deltaLat = 0.085
            val deltaLng = 0.125
            val popularDb = orderRepository.findPopularDestinationsNearby(
                minLat = gridLat - deltaLat,
                maxLat = gridLat + deltaLat,
                minLng = gridLng - deltaLng,
                maxLng = gridLng + deltaLng
            )
            var dbAdded = 0
            for (pop in popularDb) {
                val dist = computeHaversineMeters(gridLat, gridLng, pop.lat, pop.lng)
                if (dist < MIN_GRID_CANDIDATE_DISTANCE_METERS || dist > MAX_GRID_CANDIDATE_DISTANCE_METERS) continue

                val cleanTitle = pop.address.substringBefore(" (").trim()
                val citySubtitle = pop.address.substringAfter("(", "").substringBefore(")").trim()
                    .ifEmpty { if (lang == "en") "Popular destination" else "Популярна адреса" }
                val normKey = normalizeDedupKey(cleanTitle)
                if (cleanTitle.length >= 4) {
                    val dto = NearbyPlaceSuggestionDto(
                        placeId = "srv_pop_${normKey.hashCode()}",
                        title = cleanTitle,
                        subtitle = citySubtitle,
                        lat = pop.lat,
                        lng = pop.lng,
                        distanceMeters = dist,
                        category = "popular"
                    )
                    if (addCandidateIfSpread(dto, 900)) {
                        dbAdded++
                        if (dbAdded >= 3) break
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug(">>> [GeocodingService] Не удалось получить популярные адреса из БД: ${e.message}")
        }

        // 2. Спиральные сателлиты на РАЗНЫХ расстояниях и в разных направлениях от клиента:
        //    - Точка 1 (Север): ~2.4 км
        //    - Точка 2 (Юго-Восток): ~3.0 км
        //    - Точка 3 (Запад): ~3.7 км
        //    - Точка 4 (Северо-Восток): ~4.5 км
        //    - Точка 5 (Юго-Запад): ~5.3 км
        //    - Точка 6 (Восток-Юг): ~6.3 км
        val spiralSatellites = listOf(
            Pair(0.0220, 0.0040),   // ~2.45 км (Север)
            Pair(-0.0190, 0.0310),  // ~3.05 км (Юго-Восток)
            Pair(0.0050, -0.0520),  // ~3.70 км (Запад)
            Pair(0.0320, 0.0390),   // ~4.50 км (Северо-Восток)
            Pair(-0.0380, -0.0450), // ~5.30 км (Юго-Запад)
            Pair(-0.0150, 0.0860)   // ~6.30 км (Восток)
        )

        // Запускаем параллельно:
        // A) Категорийный поиск важных городских объектов в Overpass (метро, трамвай, ТРЦ, больницы, парки, вокзалы)
        val overpassFuture = CompletableFuture.supplyAsync({
            fetchDiverseHubsFromOverpass(gridLat, gridLng, lang, defaultSubtitle)
        }, geoExecutor).completeOnTimeout(emptyList(), 1900, TimeUnit.MILLISECONDS)

        // B) 6 спиральных сателлитов Photon (из каждого сателлита берем максимум 1 лучший POI + 1 главную улицу!)
        val photonFutures = spiralSatellites.map { (dLat, dLng) ->
            CompletableFuture.supplyAsync({
                fetchBestFromSpiralSatellite(gridLat, gridLng, gridLat + dLat, gridLng + dLng, lang, defaultSubtitle)
            }, geoExecutor).completeOnTimeout(emptyList(), 1700, TimeUnit.MILLISECONDS)
        }

        // C) 6 спиральных точек Nominatim на тех же разных радиусах (на случай если Photon/Overpass не ответили)
        val nominatimFutures = spiralSatellites.mapIndexed { idx, (dLat, dLng) ->
            CompletableFuture.supplyAsync({
                val sLat = gridLat + dLat
                val sLng = gridLng + dLng
                val dist = computeHaversineMeters(gridLat, gridLng, sLat, sLng)
                if (dist < MIN_GRID_CANDIDATE_DISTANCE_METERS) return@supplyAsync null

                val nom = fetchFromNominatim(sLat, sLng, lang)
                if (nom != null && nom.address.isNotBlank()) {
                    val cleanTitle = nom.address.substringBefore(" (").trim()
                    val sub = nom.address.substringAfter("(", "").substringBefore(")").trim()
                        .ifEmpty { defaultSubtitle }
                    val normKey = normalizeDedupKey(cleanTitle)
                    if (cleanTitle.length >= 4 && !cleanTitle.contains("Точка на карті", ignoreCase = true)) {
                        NearbyPlaceSuggestionDto(
                            placeId = "srv_nom_${idx}_${normKey.hashCode()}",
                            title = cleanTitle,
                            subtitle = sub,
                            lat = sLat,
                            lng = sLng,
                            distanceMeters = dist,
                            category = if (isMajorStreetName(cleanTitle)) "major_street" else "street"
                        )
                    } else null
                } else null
            }, geoExecutor).completeOnTimeout(null, 1800, TimeUnit.MILLISECONDS)
        }

        // Сначала добавляем категориальные объекты из Overpass (метро, трамвай, ТРЦ, больница, парк, вокзал)
        val overpassList = try { overpassFuture.get() } catch (_: Exception) { emptyList() }
        for (cand in overpassList) {
            addCandidateIfSpread(cand, 850)
        }

        // Затем добавляем лучшие точки из каждого спирального сателлита Photon (разные районы и разный километраж)
        for (f in photonFutures) {
            val list = try { f.get() } catch (_: Exception) { emptyList() }
            for (cand in list) {
                addCandidateIfSpread(cand, 850)
            }
        }

        // Затем добираем из спиральных точек Nominatim
        for (nf in nominatimFutures) {
            val cand = try { nf.get() } catch (_: Exception) { null } ?: continue
            addCandidateIfSpread(cand, 800)
        }

        return pool.take(20)
    }

    /**
     * Опрашивает один спиральный сателлит в Photon и возвращает МАКСИМУМ 2 элемента из этого района:
     * 1 лучший важный объект (метро, остановка трамвая, ТРЦ, больница, супермаркет, парк)
     * и 1 лучшую главную улицу/проспект.
     * Это полностью исключает ситуацию, когда из одного сателлита возвращается 6 соседних домов в 10 метрах друг от друга!
     */
    private fun fetchBestFromSpiralSatellite(
        centerLat: Double,
        centerLng: Double,
        probeLat: Double,
        probeLng: Double,
        lang: String,
        defaultSubtitle: String
    ): List<NearbyPlaceSuggestionDto> {
        return try {
            val langParam = if (lang == "en") "&lang=en" else ""
            val urlStr = "https://photon.komoot.io/reverse?lat=$probeLat&lon=$probeLng&radius=1.0&limit=18$langParam"
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.setRequestProperty("User-Agent", "UnitTaxiServer/1.0 (+https://unitua.com)")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != 200) return emptyList()

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(responseText).asJsonObject
            val features = root.getAsJsonArray("features") ?: return emptyList()

            var bestPoi: NearbyPlaceSuggestionDto? = null
            var bestPoiWeight = -1

            var bestStreet: NearbyPlaceSuggestionDto? = null
            var bestStreetWeight = -1

            for (feat in features) {
                if (!feat.isJsonObject) continue
                val fObj = feat.asJsonObject
                val props = fObj.getAsJsonObject("properties") ?: continue
                val geom = fObj.getAsJsonObject("geometry") ?: continue
                val coords = geom.getAsJsonArray("coordinates") ?: continue
                if (coords.size() < 2) continue

                val fLng = coords[0].asDouble
                val fLat = coords[1].asDouble
                val distMeters = computeHaversineMeters(centerLat, centerLng, fLat, fLng)

                if (distMeters < MIN_GRID_CANDIDATE_DISTANCE_METERS || distMeters > MAX_GRID_CANDIDATE_DISTANCE_METERS) {
                    continue
                }

                val osmKey = getFirstString(props, "osm_key") ?: ""
                val osmValue = getFirstString(props, "osm_value") ?: ""
                val name = getFirstString(props, "name")
                val street = getFirstString(props, "street")
                val houseNumber = getFirstString(props, "housenumber")
                val district = getFirstString(props, "district", "suburb", "city", "locality") ?: defaultSubtitle

                // Классифицируем важные точки притяжения (транспорт, ТРЦ, больница, вуз, парк)
                if (!name.isNullOrBlank()) {
                    val classified = classifyPoi(osmKey, osmValue, name, street, houseNumber, district, lang)
                    if (classified != null && classified.third > bestPoiWeight) {
                        val (category, subtitle, weight) = classified
                        val normKey = normalizeDedupKey(name)
                        if (normKey.length >= 3) {
                            bestPoiWeight = weight
                            bestPoi = NearbyPlaceSuggestionDto(
                                placeId = "srv_ph_poi_${normKey.hashCode()}",
                                title = name.trim(),
                                subtitle = subtitle,
                                lat = fLat,
                                lng = fLng,
                                distanceMeters = distMeters,
                                category = category
                            )
                        }
                    }
                }

                // Извлекаем лучшую главную улицу / проспект в этой точке спирали
                val rawStreetName = when {
                    osmKey == "highway" && !name.isNullOrBlank() && osmValue !in listOf("bus_stop", "platform") -> name
                    !street.isNullOrBlank() -> street
                    !name.isNullOrBlank() && isStreetLikeName(name) -> name
                    else -> null
                }

                if (!rawStreetName.isNullOrBlank()) {
                    val cleanStreet = shortenStreetType(rawStreetName)
                    val streetKey = normalizeDedupKey(cleanStreet)
                    if (streetKey.length >= 3) {
                        val isMajor = isMajorStreetName(rawStreetName) || osmValue in listOf("trunk", "primary", "secondary", "tertiary")
                        val weight = if (isMajor) 90 else 60
                        if (weight > bestStreetWeight) {
                            bestStreetWeight = weight
                            val category = if (isMajor) "major_street" else "street"
                            val subtitle = if (isMajor) {
                                if (lang == "en") "Main street • $district" else "Головна вулиця • $district"
                            } else {
                                district
                            }
                            val titleWithHouse = if (!houseNumber.isNullOrBlank() && street != null && rawStreetName == street) {
                                "$cleanStreet, $houseNumber"
                            } else {
                                cleanStreet
                            }
                            bestStreet = NearbyPlaceSuggestionDto(
                                placeId = "srv_ph_st_${streetKey.hashCode()}",
                                title = titleWithHouse,
                                subtitle = subtitle,
                                lat = fLat,
                                lng = fLng,
                                distanceMeters = distMeters,
                                category = category
                            )
                        }
                    }
                }
            }

            listOfNotNull(bestPoi, bestStreet)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Запрашивает из Overpass API конкретные функциональные категории городских узлов
     * в кольце 2.2 км – 7.5 км вокруг клиента:
     * - Станции метро, вокзалы, остановки скоростного трамвая (transport)
     * - ТРЦ, универмаги, рынки (mall)
     * - Больницы и клиники (hospital)
     * - Университеты, парки, кинотеатры, площади (landmark)
     */
    private fun fetchDiverseHubsFromOverpass(
        lat: Double,
        lng: Double,
        lang: String,
        defaultSubtitle: String
    ): List<NearbyPlaceSuggestionDto> {
        return try {
            val query = """
                [out:json][timeout:2];
                (
                  node["name"]["railway"~"^(station|subway_entrance|tram_stop)$"](around:6800,$lat,$lng);
                  node["name"]["amenity"~"^(bus_station|hospital|university|marketplace|cinema)$"](around:6800,$lat,$lng);
                  way["name"]["shop"~"^(mall|department_store|supermarket)$"](around:6800,$lat,$lng);
                  way["name"]["amenity"~"^(hospital|university|marketplace)$"](around:6800,$lat,$lng);
                  way["name"]["leisure"="park"](around:6000,$lat,$lng);
                  way["name"]["place"="square"](around:6500,$lat,$lng);
                );
                out center 55;
            """.trimIndent()

            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val urlStr = "https://overpass-api.de/api/interpreter?data=$encodedQuery"
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1700
            connection.readTimeout = 1700
            connection.setRequestProperty("User-Agent", "UnitTaxiServer/1.0 (+https://unitua.com)")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != 200) return emptyList()

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(responseText).asJsonObject
            val elements = root.getAsJsonArray("elements") ?: return emptyList()

            val results = mutableListOf<NearbyPlaceSuggestionDto>()
            val seen = HashSet<String>()

            for (el in elements) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val tags = obj.getAsJsonObject("tags") ?: continue

                val rawName = when (lang) {
                    "en" -> getFirstString(tags, "name:en", "name:uk", "name")
                    else -> getFirstString(tags, "name:uk", "name", "name:ru")
                } ?: continue

                val elLat = when {
                    obj.has("lat") -> obj.get("lat").asDouble
                    obj.has("center") -> obj.getAsJsonObject("center").get("lat").asDouble
                    else -> continue
                }
                val elLng = when {
                    obj.has("lon") -> obj.get("lon").asDouble
                    obj.has("center") -> obj.getAsJsonObject("center").get("lon").asDouble
                    else -> continue
                }

                val distMeters = computeHaversineMeters(lat, lng, elLat, elLng)
                if (distMeters < MIN_GRID_CANDIDATE_DISTANCE_METERS || distMeters > MAX_GRID_CANDIDATE_DISTANCE_METERS) {
                    continue
                }

                val railway = getFirstString(tags, "railway")
                val shop = getFirstString(tags, "shop")
                val amenity = getFirstString(tags, "amenity")
                val leisure = getFirstString(tags, "leisure")
                val placeTag = getFirstString(tags, "place")
                val addrStreet = getFirstString(tags, "addr:street")
                val addrHouse = getFirstString(tags, "addr:housenumber")

                val osmKey = when {
                    railway != null -> "railway"
                    shop != null -> "shop"
                    amenity != null -> "amenity"
                    leisure != null -> "leisure"
                    placeTag != null -> "place"
                    else -> ""
                }
                val osmValue = railway ?: shop ?: amenity ?: leisure ?: placeTag ?: ""

                val classified = classifyPoi(osmKey, osmValue, rawName, addrStreet, addrHouse, defaultSubtitle, lang)
                    ?: continue

                val (category, subtitle, _) = classified
                val normKey = normalizeDedupKey(rawName)
                if (normKey.length >= 3 && seen.add(normKey)) {
                    results.add(
                        NearbyPlaceSuggestionDto(
                            placeId = "srv_osm_${normKey.hashCode()}",
                            title = rawName.trim(),
                            subtitle = subtitle,
                            lat = elLat,
                            lng = elLng,
                            distanceMeters = distMeters,
                            category = category
                        )
                    )
                }
            }

            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Классифицирует объект по категории (transport, mall, hospital, landmark) и формирует информативный подзаголовок.
     */
    private fun classifyPoi(
        osmKey: String,
        osmValue: String,
        name: String,
        street: String?,
        houseNumber: String?,
        defaultSubtitle: String,
        lang: String
    ): Triple<String, String, Int>? {
        val streetSuffix = if (!street.isNullOrBlank()) {
            if (!houseNumber.isNullOrBlank()) "${shortenStreetType(street)}, $houseNumber"
            else shortenStreetType(street)
        } else defaultSubtitle

        return when {
            osmKey == "railway" && osmValue == "subway_entrance" || name.contains("метро", ignoreCase = true) -> {
                val label = if (lang == "en") "Metro Station • $streetSuffix" else "Станція метро • $streetSuffix"
                Triple("transport", label, 100)
            }
            osmKey == "railway" && osmValue == "station" || name.contains("вокзал", ignoreCase = true) -> {
                val label = if (lang == "en") "Railway Station • $streetSuffix" else "Вокзал • $streetSuffix"
                Triple("transport", label, 100)
            }
            osmKey == "railway" && osmValue == "tram_stop" || (osmKey == "highway" && osmValue == "bus_stop" && name.contains("трамва", ignoreCase = true)) -> {
                val label = if (lang == "en") "Tram Stop • $streetSuffix" else "Зупинка трамвая • $streetSuffix"
                Triple("transport", label, 92)
            }
            osmKey == "amenity" && osmValue == "bus_station" -> {
                val label = if (lang == "en") "Bus Station • $streetSuffix" else "Автостанція • $streetSuffix"
                Triple("transport", label, 95)
            }
            osmKey == "shop" && osmValue in listOf("mall", "department_store") || name.contains("ТРЦ", ignoreCase = true) || name.contains("ТЦ ", ignoreCase = true) -> {
                val label = if (lang == "en") "Shopping Mall • $streetSuffix" else "ТРЦ • $streetSuffix"
                Triple("mall", label, 98)
            }
            osmKey == "shop" && osmValue == "supermarket" -> {
                val label = if (lang == "en") "Supermarket • $streetSuffix" else "Супермаркет • $streetSuffix"
                Triple("mall", label, 85)
            }
            osmKey == "amenity" && osmValue == "marketplace" || name.contains("ринок", ignoreCase = true) -> {
                val label = if (lang == "en") "Market • $streetSuffix" else "Ринок • $streetSuffix"
                Triple("mall", label, 88)
            }
            osmKey == "amenity" && osmValue in listOf("hospital", "clinic") || name.contains("лікарня", ignoreCase = true) -> {
                val label = if (lang == "en") "Hospital • $streetSuffix" else "Лікарня • $streetSuffix"
                Triple("hospital", label, 94)
            }
            osmKey == "amenity" && osmValue == "university" -> {
                val label = if (lang == "en") "University • $streetSuffix" else "Університет • $streetSuffix"
                Triple("landmark", label, 90)
            }
            osmKey == "leisure" && osmValue == "park" -> {
                val label = if (lang == "en") "Park • $streetSuffix" else "Парк • $streetSuffix"
                Triple("landmark", label, 86)
            }
            osmKey == "place" && osmValue == "square" -> {
                val label = if (lang == "en") "Square • $streetSuffix" else "Площа • $streetSuffix"
                Triple("landmark", label, 90)
            }
            osmKey == "amenity" && osmValue in listOf("cinema", "theatre") -> {
                val label = if (lang == "en") "Cinema / Theatre • $streetSuffix" else "Кінотеатр / Театр • $streetSuffix"
                Triple("landmark", label, 85)
            }
            else -> null
        }
    }

    private fun isMajorStreetName(raw: String): Boolean {
        return raw.contains("проспект", ignoreCase = true) ||
                raw.contains("пр-т", ignoreCase = true) ||
                raw.contains("бульвар", ignoreCase = true) ||
                raw.contains("б-р", ignoreCase = true) ||
                raw.contains("площа", ignoreCase = true) ||
                raw.contains("майдан", ignoreCase = true) ||
                raw.contains("набережна", ignoreCase = true) ||
                raw.contains("шосе", ignoreCase = true) ||
                raw.contains("кільцева", ignoreCase = true)
    }

    private fun isStreetLikeName(raw: String): Boolean {
        return isMajorStreetName(raw) ||
                raw.contains("вулиця", ignoreCase = true) ||
                raw.contains("вул.", ignoreCase = true) ||
                raw.contains("провулок", ignoreCase = true) ||
                raw.contains("пер.", ignoreCase = true)
    }

    private fun normalizeDedupKey(raw: String): String {
        return raw.lowercase()
            .replace(Regex("\\b(вул\\.|вулиця|улица|пр-т|проспект|б-р|бульвар|пл\\.|площа|м-н|майдан|пер\\.|провулок|наб\\.|набережна|ш\\.|шосе)\\b"), "")
            .replace(Regex("[^a-zа-яіїєґ0-9]"), "")
            .trim()
    }

    private fun computeHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).roundToInt()
    }

    private fun fetchFromNominatim(lat: Double, lng: Double, lang: String): ReverseGeocodeResponseDto? {
        return try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1&accept-language=$lang"
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1600
            connection.readTimeout = 1600
            connection.setRequestProperty("User-Agent", "UnitTaxiServer/1.0 (+https://unitua.com)")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JsonParser.parseString(responseText).asJsonObject
                val displayName = json.get("display_name")?.takeIf { !it.isJsonNull }?.asString
                val addressObj = json.getAsJsonObject("address")

                val formatted = buildCleanAddress(addressObj, displayName)
                if (formatted.isNotBlank()) {
                    ReverseGeocodeResponseDto(
                        address = formatted,
                        rawDisplayName = displayName,
                        source = "nominatim"
                    )
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCleanAddress(addressObj: JsonObject?, displayName: String?): String {
        if (addressObj != null) {
            val road = getFirstString(
                addressObj,
                "road", "pedestrian", "footway", "street", "square", "residential", "Suburb", "suburb", "neighbourhood"
            )
            val houseNumber = getFirstString(addressObj, "house_number", "building")
            val city = getFirstString(addressObj, "city", "town", "village", "municipality", "hamlet")
            val poi = getFirstString(addressObj, "amenity", "shop", "office", "tourism", "building")

            val streetPart = when {
                !road.isNullOrBlank() && !houseNumber.isNullOrBlank() -> "${shortenStreetType(road)}, $houseNumber"
                !road.isNullOrBlank() -> shortenStreetType(road)
                !poi.isNullOrBlank() -> poi
                else -> ""
            }

            if (streetPart.isNotBlank()) {
                return if (!city.isNullOrBlank() && !streetPart.equals(city, ignoreCase = true)) {
                    "$streetPart ($city)"
                } else {
                    streetPart
                }
            }
        }

        if (!displayName.isNullOrBlank()) {
            val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                return parts.take(2).joinToString(", ")
            }
        }

        return ""
    }

    private fun getFirstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull) {
                val v = obj.get(key).asString.trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    private fun shortenStreetType(raw: String): String {
        var result = raw.trim()
        val replacements = mapOf(
            "вулиця" to "вул.",
            "улица" to "вул.",
            "проспект" to "пр-т",
            "провулок" to "пер.",
            "переулок" to "пер.",
            "набережна" to "наб.",
            "набережная" to "наб.",
            "бульвар" to "б-р",
            "шосе" to "ш.",
            "шоссе" to "ш.",
            "площа" to "пл.",
            "площадь" to "пл.",
            "майдан" to "м-н"
        )
        for ((full, short) in replacements) {
            result = result.replace(Regex("\\b$full\\b", RegexOption.IGNORE_CASE), short)
        }
        return result
    }
}