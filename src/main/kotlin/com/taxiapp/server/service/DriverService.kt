package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.DriverSearchSettingsDto
import com.taxiapp.server.dto.driver.DriverSearchStateDto
import com.taxiapp.server.dto.driver.UpdateDisabilityRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.finance.WalletTransaction
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.SectorRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.repository.WalletTransactionRepository // <--- ДОДАНО
import org.springframework.data.domain.PageRequest // <--- ДОДАНО
import org.springframework.data.domain.Sort // <--- ДОДАНО
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.geo.Point
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random


@Service
class DriverService(
    private val driverRepository: DriverRepository,
    private val sectorRepository: SectorRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val smsService: SmsService,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val userRepository: UserRepository,
    private val driverCardRepository: com.taxiapp.server.repository.DriverCardRepository,
    // Находим конструктор DriverService и добавляем туда:
    private val taxiOrderRepository: com.taxiapp.server.repository.TaxiOrderRepository,
    private val redisTemplate: org.springframework.data.redis.core.StringRedisTemplate,
    private val redisTemplateAny: RedisTemplate<String, Any> // ДОБАВЛЕНО
) {


    @Transactional(readOnly = true)
    fun getDriverProfile(user: User): DriverDto {
        val driver = driverRepository.findById(user.id)
            .orElseThrow { RuntimeException("Водій не знайдений") }
            
        return DriverDto(driver)
    }

    // --- ОБНОВЛЕНИЕ ОСНОВНОГО ПРОФИЛЯ ---
    @Transactional
    fun updateProfile(driver: Driver, request: UpdateDriverRequest): DriverDto {
        val savedDriver = driverRepository.save(driver)
        return DriverDto(savedDriver)
    }

    // --- ОБНОВЛЕНИЕ МЕДИЦИНСКИХ ДАННЫХ ---
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
        // Записываем правильный баланс в зависимости от типа операции для старых данных
        transactions.forEach { if (it.balanceAfter == 0.0) { it.balanceAfter = driver.balance } }
        return transactions
    }

    // Получение незавершенных транзакций (Для экрана "Ваші кошти")
    fun getPendingDriverTransactions(driver: Driver): List<WalletTransaction> {
        val pageable = PageRequest.of(0, 50, Sort.by("createdAt").descending())
        return walletTransactionRepository.findAllByDriverIdAndStatusOrderByCreatedAtDesc(
            driver.id!!, 
            com.taxiapp.server.model.enums.TransactionStatus.PENDING, 
            pageable
        ).content
    }

    // --- ЛОГИКА УПРАВЛЕНИЯ КАРТАМИ ВОДИТЕЛЯ ---
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
            isMain = cards.isEmpty() // Если первая карта — делаем основной
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
    fun selectMainCard(driverId: Long, cardId: Long) {
        val cards = driverCardRepository.findAllByDriverId(driverId)
        cards.forEach { card ->
            card.isMain = (card.id == cardId)
        }
        driverCardRepository.saveAll(cards)
    }

    // --- ОБНОВЛЕНИЕ СТАТУСА (ОНЛАЙН/ОФФЛАЙН) ---
    // --- ОБНОВЛЕНИЕ СТАТУСА (ОНЛАЙН/ОФФЛАЙН) ---
    @Transactional
    fun updateDriverStatus(driver: Driver, request: UpdateDriverStatusRequest): DriverDto {
        if (request.isOnline) {
            driver.isOnline = true
            driver.latitude = request.latitude
            driver.longitude = request.longitude
            driver.lastUpdate = LocalDateTime.now()
            
        } else {
            // --- МЕТОД ВЫХОДА В ОФЛАЙН ---
            val activeStatuses = listOf(
                com.taxiapp.server.model.enums.OrderStatus.ACCEPTED,
                com.taxiapp.server.model.enums.OrderStatus.DRIVER_ARRIVED,
                com.taxiapp.server.model.enums.OrderStatus.IN_PROGRESS,
                com.taxiapp.server.model.enums.OrderStatus.SCHEDULED
            )
            val hasActiveOrders = taxiOrderRepository.findAllByDriverId(driver.id!!)
                .any { it.status in activeStatuses }

            if (hasActiveOrders) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Неможливо вийти в офлайн під час виконання замовлення")
            }

            driver.isOnline = false
            
            if (driver.searchMode == DriverSearchMode.CHAIN || driver.searchMode == DriverSearchMode.HOME) {
                driver.searchMode = DriverSearchMode.MANUAL
            }
        }
        
        val updatedDriver = driverRepository.save(driver)
        val driverDto = DriverDto(updatedDriver)

        // 🔥 СИНХРОНИЗАЦИЯ С REAL-TIME КЭШЕМ REDIS
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
                "bearing" to (existingMeta?.get("bearing")?.toString() ?: "0.0")
            )
            redisTemplateAny.opsForHash<String, Any>().put(metaKey, driverIdStr, updatedMeta)
            
            if (updatedDriver.longitude != null && updatedDriver.latitude != null) {
                redisTemplateAny.opsForGeo().add(geoKey, Point(updatedDriver.longitude!!, updatedDriver.latitude!!), driverIdStr)
            }
        } else {
            // СТД: Оставляем водителя в GEO-индексе, чтобы планировщик его видел, но меняем статус в мете на false
            val existingMeta = redisTemplateAny.opsForHash<String, Any>().get(metaKey, driverIdStr) as? Map<*, *>
            val updatedMeta = (existingMeta?.toMutableMap() ?: mutableMapOf()).apply {
                this["fullName"] = updatedDriver.fullName ?: "Водій"
                this["carModel"] = updatedDriver.car?.model ?: "Не вказано"
                this["carColor"] = updatedDriver.car?.color ?: ""
                this["status"] = updatedDriver.searchMode.name
                this["isOnline"] = "false"
            }
            redisTemplateAny.opsForHash<String, Any>().put(metaKey, driverIdStr, updatedMeta)
        }

        messagingTemplate.convertAndSend("/topic/admin/drivers", driverDto)
        return driverDto
    }

    // --- СМЕНА НОМЕРА ТЕЛЕФОНА ---

    fun sendVerificationCodeToCurrentPhone(user: User) {
        val phone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")
        val code = Random.nextInt(100000, 999999).toString()
        
        // Сохраняем код в Redis на 10 минут, сбрасываем старые попытки
        redisTemplate.opsForValue().set("driver:phone:current-code:$phone", code, 10, java.util.concurrent.TimeUnit.MINUTES)
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
        if (savedCode == null) throw RuntimeException("Код застарів або не існує")

        // --- ЗАЩИТА: Бэкдор "000000" ПОЛНОСТЬЮ УДАЛЕН ---
        if (savedCode != code) {
            val newAttempts = attempts + 1
            if (newAttempts >= 3) {
                redisTemplate.delete(codeKey)
                redisTemplate.delete(attemptsKey)
                throw RuntimeException("Перевищено кількість спроб. Код анульовано.")
            }
            redisTemplate.opsForValue().set(attemptsKey, newAttempts.toString(), 10, java.util.concurrent.TimeUnit.MINUTES)
            throw RuntimeException("Невірний код. Залишилось спроб: ${3 - newAttempts}")
        }

        // Код верный — генерируем токен и сохраняем связь в Redis на 5 минут
        val token = UUID.randomUUID().toString()
        redisTemplate.opsForValue().set("driver:phone:change-token:$token", phone, 5, java.util.concurrent.TimeUnit.MINUTES)
        
        // Зачищаем использованные ключи кода
        redisTemplate.delete(codeKey)
        redisTemplate.delete(attemptsKey)
        return token
    }

    fun sendVerificationCodeToNewPhone(newPhone: String) {
        if (userRepository.existsByUserPhone(newPhone)) {
            throw RuntimeException("Цей номер вже зареєстрований")
        }
        val code = Random.nextInt(100000, 999999).toString()
        
        // Сохраняем в Redis на 10 минут
        redisTemplate.opsForValue().set("driver:phone:new-code:$newPhone", code, 10, java.util.concurrent.TimeUnit.MINUTES)
        redisTemplate.delete("driver:phone:new-attempts:$newPhone")
        
        smsService.sendSms(newPhone, "Код підтвердження нового номера: $code")
    }

    @Transactional
    fun changePhone(user: User, newPhone: String, code: String, changeToken: String): User {
        val currentPhone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")

        // Проверяем токен владения операцией в Redis
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
        if (savedCode == null) throw RuntimeException("Код для нового номера застарів")

        if (savedCode != code) {
            val newAttempts = attempts + 1
            if (newAttempts >= 3) {
                redisTemplate.delete(codeKey)
                redisTemplate.delete(attemptsKey)
                redisTemplate.delete(tokenKey)
                throw RuntimeException("Перевищено спроби для нового номера. Процес анульовано.")
            }
            redisTemplate.opsForValue().set(attemptsKey, newAttempts.toString(), 10, java.util.concurrent.TimeUnit.MINUTES)
            throw RuntimeException("Невірний код нового номера. Залишилось спроб: ${3 - newAttempts}")
        }

        // Все проверки пройдены — выжигаем токены из Redis и обновляем БД
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

    // --- ПОИСК И НАСТРОЙКИ (ИСПРАВЛЕНО ДЛЯ УДАЛЕНИЯ ЭФИРА) ---

    @Transactional
    fun getSearchState(driver: Driver): DriverSearchStateDto {
        checkAndResetHomeLimit(driver)
        
        val sectorNames = driver.homeSectors.joinToString(", ") { it.name }
        val sectorIds = driver.homeSectors.mapNotNull { it.id }

        // --- СТАЛО: Возвращаем чистый режим из базы данных без подмен ---
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
        // Проверка активности (пример)
        // Если активность низкая, можно запретить переключение в HOME, но CHAIN должен работать.
        // if (driver.activityScore <= 400 && settings.mode == DriverSearchMode.HOME) { ... }

        checkAndResetHomeLimit(driver) 

        // Обработка режима "Домой"
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

        // Возвращаем обновленный статус (используем наш метод getSearchState для консистентности)
        return getSearchState(driver)
    }

    private fun checkAndResetHomeLimit(driver: Driver) {
        val now = LocalDateTime.now()
        val lastUsage = driver.lastHomeUsageDate
        
        // Сбрасываем лимит, если наступил новый день
        if (lastUsage == null || lastUsage.toLocalDate() != now.toLocalDate()) {
            driver.homeRidesLeft = 2
            // Дата обновится, когда он реально возьмет заказ "Домой", но здесь мы просто обновляем счетчик для отображения
            if (driver.homeRidesLeft != 2) {
                 driver.homeRidesLeft = 2
                 driverRepository.save(driver)
            }
        }
    }

    // --- ЛОГІКА ВИДАЛЕННЯ АКАУНТА ---

    @Transactional
    fun requestAccountDeletion(driver: Driver) {
        driver.deletionRequestedAt = LocalDateTime.now()
        driver.isOnline = false
        driver.searchMode = DriverSearchMode.OFFLINE
        driver.fcmToken = null // Очищаємо токен, щоб не йшли пуші
        driverRepository.save(driver)
    }

    @Transactional
    fun restoreAccount(driver: Driver): DriverDto {
        driver.deletionRequestedAt = null
        val saved = driverRepository.save(driver)
        return DriverDto(saved)
    }

    // Автоматичне анонімізування через 30 днів (запускається щодня о 03:00 ночі)
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun processPendingDeletions() {
        val threshold = LocalDateTime.now().minusDays(30)
        val driversToDelete = driverRepository.findAllPendingDeletionBefore(threshold)
        
        for (driver in driversToDelete) {
            // Анонімізуємо дані, але залишаємо в базі для фінансової історії
            driver.userLogin = "deleted_${driver.id}"
            driver.userPhone = "deleted_${driver.id}"
            driver.fullName = "Видалений Водій"
            driver.isBlocked = true
            driver.latitude = null
            driver.longitude = null
            driver.fcmToken = null
            // Можна також стерти номер авто, документи тощо
            
            driverRepository.save(driver)
        }
    }
    
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    fun resetAllDailyLimits() {
        // Можно реализовать массовый сброс, если нужно
    }
}