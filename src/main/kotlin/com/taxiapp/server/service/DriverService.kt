package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.DriverSearchSettingsDto
import com.taxiapp.server.dto.driver.DriverSearchStateDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.SectorRepository
import com.taxiapp.server.repository.UserRepository
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
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
    private val userRepository: UserRepository
) {
    // Временное хранилище для кодов подтверждения (Номер -> Код)
    private val verificationCodes = ConcurrentHashMap<String, String>()
    
    // Временное хранилище токенов смены телефона (Токен -> Старый номер телефона)
    private val changePhoneTokens = ConcurrentHashMap<String, String>()

    @Transactional(readOnly = true)
    fun getDriverProfile(user: User): DriverDto {
        val driver = driverRepository.findById(user.id)
            .orElseThrow { RuntimeException("Водій не знайдений") }
            
        return DriverDto(driver)
    }

    @Transactional
    fun updateDriverStatus(driver: Driver, request: UpdateDriverStatusRequest): DriverDto {
        if (request.isOnline) {
            driver.isOnline = true
            driver.latitude = request.latitude
            driver.longitude = request.longitude
            driver.lastUpdate = LocalDateTime.now()
            
            if (driver.searchMode == DriverSearchMode.OFFLINE) {
                driver.searchMode = DriverSearchMode.MANUAL
            }
        } else {
            driver.isOnline = false
            driver.searchMode = DriverSearchMode.OFFLINE 
        }
        
        val updatedDriver = driverRepository.save(driver)
        val driverDto = DriverDto(updatedDriver)

        messagingTemplate.convertAndSend("/topic/admin/drivers", driverDto)

        return driverDto
    }

    // --- ЛОГИКА СМЕНЫ НОМЕРА ТЕЛЕФОНА ---

    fun sendVerificationCodeToCurrentPhone(user: User) {
        val phone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")
        
        // ИСПРАВЛЕНО: Теперь 6 цифр (100000..999999)
        val code = Random.nextInt(100000, 999999).toString()
        
        // Сохраняем код в память
        verificationCodes[phone] = code
        
        // Используем существующий метод sendSms
        smsService.sendSms(phone, "Код зміни номера: $code")
    }

    fun verifyCurrentPhoneCode(user: User, code: String): String {
        val phone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")
        
        // "Магический код" для тестов (если нужно)
        if (code == "000000") {
             val token = UUID.randomUUID().toString()
             changePhoneTokens[token] = phone
             return token
        }

        val savedCode = verificationCodes[phone]

        if (savedCode != null && savedCode == code) {
            verificationCodes.remove(phone) // Код использован
            
            // Генерируем простой токен для смены
            val token = UUID.randomUUID().toString()
            changePhoneTokens[token] = phone // Запоминаем, кто подтвердил
            
            return token
        } else {
            throw RuntimeException("Невірний код")
        }
    }

    fun sendVerificationCodeToNewPhone(newPhone: String) {
        if (userRepository.existsByUserPhone(newPhone)) {
            throw RuntimeException("Цей номер вже зареєстрований")
        }
        
        // ИСПРАВЛЕНО: Теперь 6 цифр (100000..999999)
        val code = Random.nextInt(100000, 999999).toString()
        
        verificationCodes[newPhone] = code
        smsService.sendSms(newPhone, "Код підтвердження нового номера: $code")
    }

    @Transactional
    // ВАЖНО: Возвращаем User, чтобы контроллер мог выдать новый токен
    fun changePhone(user: User, newPhone: String, code: String, changeToken: String): User {
        val currentPhone = user.userPhone ?: throw RuntimeException("Телефон не знайдено")

        // 1. Проверяем токен смены (что шаг 1 пройден)
        val savedPhoneForToken = changePhoneTokens[changeToken]
        if (savedPhoneForToken == null || savedPhoneForToken != currentPhone) {
             throw RuntimeException("Помилка безпеки: підтвердіть старий номер знову")
        }
        
        // 2. Проверяем код на НОВОМ номере
        // Магический код для тестов
        if (code == "000000") {
             // skip check
        } else {
            val savedCode = verificationCodes[newPhone]
            if (savedCode == null || savedCode != code) {
                throw RuntimeException("Невірний код для нового номера")
            }
        }

        // 3. Очистка и смена номера
        verificationCodes.remove(newPhone)
        changePhoneTokens.remove(changeToken)

        user.userPhone = newPhone
        // Если логин совпадал с телефоном, меняем и логин
        if (user.userLogin == currentPhone) {
            user.userLogin = newPhone
        }
        
        // Возвращаем обновленного пользователя
        return userRepository.save(user)
    }

    fun updateRnokpp(user: User, rnokpp: String) {
        val driver = driverRepository.findById(user.id)
            .orElseThrow { RuntimeException("Водій не знайдений") }
        
        driver.rnokpp = rnokpp
        driverRepository.save(driver)
    }

    // --- МЕТОДЫ ПОИСКА И НАСТРОЕК (оставлены без изменений) ---

    @Transactional(readOnly = true)
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
        if (settings.mode != DriverSearchMode.MANUAL && driver.activityScore <= 400) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Низька активність (<400). Автопошук заблоковано.")
        }

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

    private fun checkAndResetHomeLimit(driver: Driver) {
        val now = LocalDateTime.now()
        val lastUsage = driver.lastHomeUsageDate
        
        if (lastUsage == null || lastUsage.toLocalDate() != now.toLocalDate()) {
            driver.homeRidesLeft = 2
            driver.lastHomeUsageDate = now
            driverRepository.save(driver)
        }
    }
    
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    fun resetAllDailyLimits() {
    }
}