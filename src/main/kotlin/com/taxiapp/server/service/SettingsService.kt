package com.taxiapp.server.service

import com.taxiapp.server.model.setting.AppSetting
import com.taxiapp.server.repository.AppSettingRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service
class SettingsService(
    private val repository: AppSettingRepository,
    @Value("\${evos.base-url:http://127.0.0.1:8080/api}") private val defaultEvosUrl: String
) {
    // Папка для сохранения картинок
    private val uploadDir = "uploads/settings"

    // Единый companion object для всех констант
    companion object {
        const val KEY_COMMISSION_PERCENT = "driver_commission_percent"
        const val KEY_ENABLE_CARD_PAYMENT = "enable_card_payment"
        const val KEY_ENABLE_DRIVER_CARD_PAYMENT = "enable_driver_card_payment"
    }

    fun getAllSettings(): Map<String, String?> {
        return repository.findAll().associate { it.key to it.value }
    }

    fun getSettingValue(key: String): String? {
        return repository.findById(key).map { it.value }.orElse(null)
    }

    // --- НАСТРОЙКА КОМИССИИ ВОДИТЕЛЯ ---
    fun getDriverCommissionPercent(): Double {
        val setting = repository.findById(KEY_COMMISSION_PERCENT).orElse(null)
        return setting?.value?.toDoubleOrNull() ?: 10.0
    }

    // --- НАСТРОЙКИ ИНТЕГРАЦИИ С EVOS ---
    fun isEvosEnabled(): Boolean {
        return getSettingValue("evos_enabled")?.toBoolean() ?: false
    }

    fun getEvosDelaySeconds(): Long {
        return getSettingValue("evos_delay_seconds")?.toLongOrNull() ?: 60L
    }

    fun getEvosUrl(): String {
        return getSettingValue("evos_url") ?: defaultEvosUrl
    }

    fun getEvosLogin(): String {
        return getSettingValue("evos_login") ?: ""
    }

    fun getEvosPassword(): String {
        return getSettingValue("evos_password") ?: ""
    }

    fun getEvosAppId(): String {
        return getSettingValue("evos_app_id") ?: "UNIT_TAXI"
    }

    // --- НАСТРОЙКИ СПОСОБОВ ОПЛАТЫ ---
    fun isCardPaymentEnabled(): Boolean {
        return getSettingValue(KEY_ENABLE_CARD_PAYMENT)?.toBoolean() ?: true
    }

    fun isDriverCardPaymentEnabled(): Boolean {
        return getSettingValue(KEY_ENABLE_DRIVER_CARD_PAYMENT)?.toBoolean() ?: true
    }

    fun getPaymentSettings(): Map<String, Boolean> {
        return mapOf(
            KEY_ENABLE_CARD_PAYMENT to isCardPaymentEnabled(),
            KEY_ENABLE_DRIVER_CARD_PAYMENT to isDriverCardPaymentEnabled()
        )
    }

    fun uploadSettingImage(key: String, file: MultipartFile): String {
        val directory = File(uploadDir)
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val extension = file.originalFilename?.substringAfterLast(".", "png") ?: "png"
        val fileName = "$key.$extension"
        val filePath = Paths.get(uploadDir, fileName)

        Files.copy(file.inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)

        val fileUrl = "/uploads/settings/$fileName"

        val setting = repository.findById(key).orElse(AppSetting(key, null))
        setting.value = fileUrl
        repository.save(setting)

        return fileUrl
    }

    fun saveSettings(settings: Map<String, String>) {
        settings.forEach { (key, value) ->
            val setting = repository.findById(key).orElse(AppSetting(key, null))
            setting.value = value
            repository.save(setting)
        }
    }
}