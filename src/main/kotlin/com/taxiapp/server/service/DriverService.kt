package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.DriverSearchSettingsDto
import com.taxiapp.server.dto.driver.DriverSearchStateDto
import com.taxiapp.server.dto.driver.UpdateDisabilityRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.finance.WalletTransaction
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.CarBrandRepository
import com.taxiapp.server.repository.CarModelRepository
import com.taxiapp.server.repository.CarTariffRepository
import com.taxiapp.server.repository.DriverCardRepository
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.SectorRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.repository.WalletTransactionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.geo.Point
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class DriverService(
    private val driverRepository: DriverRepository,
    private val sectorRepository: SectorRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val smsService: SmsService,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val userRepository: UserRepository,
    private val driverCardRepository: DriverCardRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val redisTemplate: StringRedisTemplate,
    private val redisTemplateAny: RedisTemplate<String, Any>,
    private val carBrandRepository: CarBrandRepository,
    private val carModelRepository: CarModelRepository,
    private val carClassifierService: CarClassifierService,
    private val tariffRepository: CarTariffRepository
) {
    private val secureRandom = SecureRandom()

    @Transactional(readOnly = true)
    fun getDriverProfile(user: User): DriverDto {
        val driver = driverRepository.findById(user.id)
            .orElseThrow { RuntimeException("Водій не знайдений") }

        return DriverDto(driver)
    }

    @Transactional
    fun updateProfile(driver: Driver, request: UpdateDriverRequest): DriverDto {
        val savedDriver = driverRepository.save(driver)
        return DriverDto(savedDriver)
    }

    @Transactional
    fun updateDisabilityStatus(driverId: Long, request: UpdateDisabilityRequest) {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { RuntimeException("Водій не знайдений") }

        driver.hasMovementIssue = request.hasMovementIssue
        driver.hasHearingIssue = request.hasHearingIssue
        driver.isDeaf = request.isDeaf
        driver.hasSpeechIssue = request.hasSpeechIssue

        driverRepository.save(driver)
    }

    fun getDriverTransactions(driver: Driver): List<WalletTransaction> {
        val pageable = PageRequest.of(0, 50, Sort.by("createdAt").descending())
        val transactions = walletTransactionRepository.findAllByDriverIdOrderByCreatedAtDesc(driver.id!!, pageable).content
        transactions.forEach { if (it.balanceAfter == 0.0) { it.balanceAfter = driver.balance } }
        return transactions
    }

    fun getPendingDriverTransactions(driver: Driver): List<WalletTransaction> {
        val pageable = PageRequest.of(0, 50, Sort.by("createdAt").descending())
        return walletTransactionRepository.findAllByDriverIdAndStatusOrderByCreatedAtDesc(
            driver.id!!,
            com.taxiapp.server.model.enums.TransactionStatus.PENDING,
            pageable
        ).content
    }

    @Transactional(readOnly = true)
    fun getDriverCards(driverId: Long): List<com.taxiapp.server.model.user.DriverCard> {
        return driverCardRepository.findAllByDriverId(driverId)
    }

    @Transactional
    fun completeDriverCardBinding(driverId: Long, cardToken: String, cardMask: String) {
        val driver = driverRepository.findById(driverId).orElseThrow { RuntimeException("Водій не знайдений") }
        val cards = driverCardRepository.findAllByDriverId(driverId)

        val newCard = com.taxiapp.server.model.user.DriverCard(
            driver = driver,
            cardNumber = cardMask,
            cardToken = cardToken,
            cardHolder = "Картка Водія",
            isMain = cards.isEmpty()
        )
        driverCardRepository.save(newCard)
    }

    @Transactional
    fun deleteDriverCard(driverId: Long, cardId: Long) {
        val card = driverCardRepository.findById(cardId).orElseThrow { RuntimeException("Картку не знайдено") }
        if (card.driver.id != driverId) throw RuntimeException("Помилка безпеки")

        driverCardRepository.delete(card)

        if (card.isMain) {
            val remaining = driverCardRepository.findAllByDriverId(driverId)
            if (remaining.isNotEmpty()) {
                remaining.first().isMain = true
                driverCardRepository.save(remaining.first())
            }
        }
    }

    @Transactional
    fun updateDriverAllowedTariffsForCar(driver: Driver, car: Car) {
        val cityName = driver.city ?: "Київ"

        // Загружаем список всех тарифов РОВНО 1 РАЗ вместо 4-х вызовов findAll()
        val allTariffs = tariffRepository.findAll()

        val brand = carBrandRepository.findAll()
            .find { it.name.equals(car.make, ignoreCase = true) }

        val model = brand?.let { b ->
            carModelRepository.findAll()
                .filter { it.brand.id == b.id }
                .find { it.name.equals(car.model, ignoreCase = true) }
        }

        val newAllowed = if (model != null) {
            val eval = carClassifierService.evaluateCar(
                com.taxiapp.server.dto.classifier.EvaluateCarRequest(
                    cityName = cityName,
                    modelId = model.id,
                    year = car.year
                )
            )

            allTariffs.filter { tariff ->
                eval.allowedTariffs.any { allowed ->
                    tariff.name.contains(allowed, ignoreCase = true) ||
                    (allowed == "STANDARD" && tariff.name.contains("Стандарт", ignoreCase = true)) ||
                    (allowed == "COMFORT" && tariff.name.contains("Комфорт", ignoreCase = true)) ||
                    (allowed == "BUSINESS" && tariff.name.contains("Бізнес", ignoreCase = true))
                }
            }.toMutableSet()
        } else {
            allTariffs.filter {
                it.name.contains("Стандарт", ignoreCase = true) || it.name.contains("STANDARD", ignoreCase = true)
            }.toMutableSet()
        }

        if (!car.carType.isNullOrBlank()) {
            val normalizedCarType = car.carType!!.trim().uppercase()
            val bodyTariffs = allTariffs.filter { tariff ->
                tariff.bodyType != null && (
                    tariff.bodyType!!.equals(normalizedCarType, ignoreCase = true) ||
                    (normalizedCarType.contains("UNIVERSAL") && tariff.bodyType!!.contains("UNIVERSAL", ignoreCase = true)) ||
                    (normalizedCarType.contains("MINIBUS") && tariff.bodyType!!.contains("MINIBUS", ignoreCase = true)) ||
                    (normalizedCarType.contains("УНІВЕРСАЛ") && tariff.bodyType!!.contains("UNIVERSAL", ignoreCase = true)) ||
                    (normalizedCarType.contains("МІКРОАВТОБУС") && tariff.bodyType!!.contains("MINIBUS", ignoreCase = true))
                )
            }
            newAllowed.addAll(bodyTariffs)
        }

        val finalAllowed = if (newAllowed.isEmpty()) allTariffs.toMutableSet() else newAllowed

        driver.allowedTariffs = finalAllowed

        val allowedIds = finalAllowed.map { it.id }.toSet()
        driver.selectedTariffs.retainAll { it.id in allowedIds }

        if (driver.selectedTariffs.isEmpty()) {
            driver.selectedTariffs.addAll(finalAllowed)
        }

        driverRepository.save(driver)
    }

    @Transactional
    fun selectMainCard(driverId: Long, cardId: Long) {
        val cards = driverCardRepository.findAllByDriverId(driverId)
        cards.forEach { card ->
            card.isMain = (card.id == cardId)
        }
        driverCardRepository.saveAll(cards)
    }

    @Transactional
    fun updateDriverStatus(driver: Driver, request: UpdateDriverStatusRequest): DriverDto {
        if (request.isOnline) {
            driver.isOnline = true
            driver.latitude = request.latitude
            driver.longitude = request.longitude
            driver.lastUpdate = LocalDateTime.now()
        } else {
            // HIGH-LOAD ФИКС: Вместо выгрузки ВСЕХ исторических заказов водителя (findAllByDriverId)
            // делаем мгновенный индексный поиск активного заказа
            val activeStatuses = listOf(
                OrderStatus.ACCEPTED,
                OrderStatus.DRIVER_ARRIVED,
                OrderStatus.IN_PROGRESS,
                OrderStatus.SCHEDULED
            )
            val hasActiveOrder = taxiOrderRepository.findActiveOrderByDriverId(driver.id!!).isPresent ||
                taxiOrderRepository.findByDriverAndStatusIn(driver, activeStatuses) != null

            if (hasActiveOrder) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Неможливо вийти в офлайн під час виконання замовлення")
            }

            driver.isOnline = false

            if (driver.searchMode == DriverSearchMode.CHAIN || driver.searchMode == DriverSearchMode.HOME) {
                driver.searchMode = DriverSearchMode.MANUAL
            }
        }

        val updatedDriver = driverRepository.save(driver)
        val driverDto = DriverDto(updatedDriver)

        val driverIdStr = updatedDriver.id.toString()
        val metaKey = "drivers:meta"
        val geoKey = "drivers:geo"

        if (updatedDriver.isOnline) {
            val existingMeta = redisTemplateAny.opsForHash<String, Any>().get(metaKey, driverIdStr) as? Map<*, *>
            val fullName = updatedDriver.fullName ?: "Водій"
            val carModel = updatedDriver.car?.model ?: "Не вказано"
            val carColor = updatedDriver.car?.color ?: ""
            val searchMode = updatedDriver.searchMode.name

            val updatedMeta = mapOf(
                "fullName" to fullName,
                "carModel" to carModel,
                "carColor" to carColor,
                "status" to searchMode,
                "isOnline" to "true",
                "lat" to (updatedDriver.latitude?.toString() ?: existingMeta?.get("lat")?.toString() ?: "0.0"),
                "lng" to (updatedDriver.longitude?.toString() ?: existingMeta?.get("lng")?.toString() ?: "0.0"),
                "bearing" to (existingMeta?.get("bearing")?.toString() ?: "0.0"),
                "updatedAt" to System.currentTimeMillis().toString()
            )
            redisTemplateAny.opsForHash<String, Any>().put(metaKey, driverIdStr, updatedMeta)

            if (updatedDriver.longitude != null && updatedDriver.latitude != null) {
                redisTemplateAny.opsForGeo().add(geoKey, Point(updatedDriver.longitude!!, updatedDriver.latitude!!), driverIdStr)
            }
        } else {
            val existingMeta = redisTemplateAny.opsForHash<String, Any>().get(metaKey, driverIdStr) as? Map<*, *>
            val updatedMeta = (existingMeta?.toMutableMap() ?: mutableMapOf()).apply {
                this["fullName"] = updatedDriver.fullName ?: "Водій"
                this["carModel"] = updatedDriver.car?.model ?: "Не вказано"
                this["carColor"] = updatedDriver.car?.color ?: ""
                this["status"] = updatedDriver.searchMode.name
                this["isOnline"] = "false"
            }
            redisTemplateAny.opsForHash<String, Any>().put(metaKey, driverIdStr, updatedMeta)
            redisTemplateAny.opsForGeo().remove(geoKey, driverIdStr)
        }

        messagingTemplate.convertAndSend("/topic/admin/drivers", driverDto)
        return driverDto
    }

    fun sendVerificationCodeToCurrentPhone(user: User) {
        val phone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")
        val code = (100000 + secureRandom.nextInt(900000)).toString()

        redisTemplate.opsForValue().set("driver:phone:current-code:$phone", code, 10, TimeUnit.MINUTES)
        redisTemplate.delete("driver:phone:current-attempts:$phone")

        smsService.sendSms(phone, "Код зміни номера: $code")
    }

    fun verifyCurrentPhoneCode(user: User, code: String): String {
        val phone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")
        val attemptsKey = "driver:phone:current-attempts:$phone"
        val codeKey = "driver:phone:current-code:$phone"

        val attempts = redisTemplate.opsForValue().get(attemptsKey)?.toIntOrNull() ?: 0
        if (attempts >= 3) {
            redisTemplate.delete(codeKey)
            throw RuntimeException("Перевищено кількість спроб. Надішліть код повторно.")
        }

        val savedCode = redisTemplate.opsForValue().get(codeKey)
            ?: throw RuntimeException("Код застарів або не існує")

        if (savedCode != code) {
            val newAttempts = attempts + 1
            if (newAttempts >= 3) {
                redisTemplate.delete(codeKey)
                redisTemplate.delete(attemptsKey)
                throw RuntimeException("Перевищено кількість спроб. Код анульовано.")
            }
            redisTemplate.opsForValue().set(attemptsKey, newAttempts.toString(), 10, TimeUnit.MINUTES)
            throw RuntimeException("Невірний код. Залишилось спроб: ${3 - newAttempts}")
        }

        val token = UUID.randomUUID().toString()
        redisTemplate.opsForValue().set("driver:phone:change-token:$token", phone, 5, TimeUnit.MINUTES)

        redisTemplate.delete(codeKey)
        redisTemplate.delete(attemptsKey)
        return token
    }

    fun sendVerificationCodeToNewPhone(newPhone: String) {
        if (userRepository.existsByUserPhone(newPhone)) {
            throw RuntimeException("Цей номер вже зареєстрований")
        }
        val code = (100000 + secureRandom.nextInt(900000)).toString()

        redisTemplate.opsForValue().set("driver:phone:new-code:$newPhone", code, 10, TimeUnit.MINUTES)
        redisTemplate.delete("driver:phone:new-attempts:$newPhone")

        smsService.sendSms(newPhone, "Код підтвердження нового номера: $code")
    }

    @Transactional
    fun changePhone(user: User, newPhone: String, code: String, changeToken: String): User {
        val currentPhone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")

        val tokenKey = "driver:phone:change-token:$changeToken"
        val savedPhoneForToken = redisTemplate.opsForValue().get(tokenKey)
        if (savedPhoneForToken == null || savedPhoneForToken != currentPhone) {
            throw RuntimeException("Помилка безпеки: підтвердіть старий номер знову")
        }

        val codeKey = "driver:phone:new-code:$newPhone"
        val attemptsKey = "driver:phone:new-attempts:$newPhone"

        val attempts = redisTemplate.opsForValue().get(attemptsKey)?.toIntOrNull() ?: 0
        if (attempts >= 3) {
            redisTemplate.delete(codeKey)
            throw RuntimeException("Перевищено спроби введення для нового номера")
        }

        val savedCode = redisTemplate.opsForValue().get(codeKey)
            ?: throw RuntimeException("Код для нового номера застарів")

        if (savedCode != code) {
            val newAttempts = attempts + 1
            if (newAttempts >= 3) {
                redisTemplate.delete(codeKey)
                redisTemplate.delete(attemptsKey)
                redisTemplate.delete(tokenKey)
                throw RuntimeException("Перевищено спроби для нового номера. Процес анульовано.")
            }
            redisTemplate.opsForValue().set(attemptsKey, newAttempts.toString(), 10, TimeUnit.MINUTES)
            throw RuntimeException("Невірний код нового номера. Залишилось спроб: ${3 - newAttempts}")
        }

        redisTemplate.delete(codeKey)
        redisTemplate.delete(attemptsKey)
        redisTemplate.delete(tokenKey)

        user.userPhone = newPhone
        if (user.userLogin == currentPhone) {
            user.userLogin = newPhone
        }

        return userRepository.save(user)
    }

    fun updateRnokpp(user: User, rnokpp: String) {
        val driver = driverRepository.findById(user.id)
            .orElseThrow { RuntimeException("Водій не знайдений") }

        driver.rnokpp = rnokpp
        driverRepository.save(driver)
    }

    @Transactional
    fun getSearchState(driver: Driver): DriverSearchStateDto {
        checkAndResetHomeLimit(driver)

        val sectorNames = driver.homeSectors.joinToString(", ") { it.name }
        val sectorIds = driver.homeSectors.mapNotNull { it.id }

        return DriverSearchStateDto(
            mode = driver.searchMode,
            radius = driver.searchRadius,
            homeSectorIds = sectorIds,
            homeSectorNames = if (sectorNames.isNotEmpty()) sectorNames else null,
            homeRidesLeft = driver.homeRidesLeft
        )
    }

    @Transactional
    fun updateSearchSettings(driver: Driver, settings: DriverSearchSettingsDto): DriverSearchStateDto {
        checkAndResetHomeLimit(driver)

        if (settings.mode == DriverSearchMode.HOME) {
            if (driver.homeRidesLeft <= 0) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Вичерпано ліміт поїздок 'Додому' на сьогодні.")
            }

            val ids = settings.homeSectorIds
            if (ids.isNullOrEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оберіть хоча б один сектор.")
            }
            if (ids.size > 30) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Максимум 30 секторів.")
            }

            val sectors = sectorRepository.findAllById(ids)
            if (sectors.isEmpty()) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Сектори не знайдено")
            }

            driver.homeSectors.clear()
            driver.homeSectors.addAll(sectors)
        }

        driver.searchMode = settings.mode
        driver.searchRadius = settings.radius.coerceIn(0.5, 30.0)
        driverRepository.save(driver)

        return getSearchState(driver)
    }

    private fun checkAndResetHomeLimit(driver: Driver) {
        val now = LocalDateTime.now()
        val lastUsage = driver.lastHomeUsageDate

        if (lastUsage == null || lastUsage.toLocalDate() != now.toLocalDate()) {
            if (driver.homeRidesLeft != 2) {
                driver.homeRidesLeft = 2
                driverRepository.save(driver)
            }
        }
    }

    @Transactional
    fun requestAccountDeletion(driver: Driver) {
        driver.deletionRequestedAt = LocalDateTime.now()
        driver.isOnline = false
        driver.searchMode = DriverSearchMode.OFFLINE
        driver.fcmToken = null
        driverRepository.save(driver)
    }

    @Transactional
    fun restoreAccount(driver: Driver): DriverDto {
        driver.deletionRequestedAt = null
        val saved = driverRepository.save(driver)
        return DriverDto(saved)
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun processPendingDeletions() {
        val threshold = LocalDateTime.now().minusDays(30)
        val driversToDelete = driverRepository.findAllPendingDeletionBefore(threshold)

        for (driver in driversToDelete) {
            driver.userLogin = "deleted_${driver.id}"
            driver.userPhone = "deleted_${driver.id}"
            driver.fullName = "Видалений Водій"
            driver.isBlocked = true
            driver.latitude = null
            driver.longitude = null
            driver.fcmToken = null

            driverRepository.save(driver)
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    fun resetAllDailyLimits() {
    }

    @Transactional
    fun updateSelectedTariffs(driverId: Long, selectedTariffIds: Set<Long>): DriverDto {
        if (selectedTariffIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Необхідно вибрати хоча б один тариф")
        }
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений") }

        val allowedTariffsMap = driver.allowedTariffs.associateBy { it.id }
        val newSelectedTariffs = selectedTariffIds.mapNotNull { allowedTariffsMap[it] }.toMutableSet()

        driver.selectedTariffs.clear()
        driver.selectedTariffs.addAll(newSelectedTariffs)

        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
}