package com.taxiapp.server.service

import com.taxiapp.server.model.setting.AppSetting
import com.taxiapp.server.repository.AppSettingRepository
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service
class SettingsService(
    private val repository: AppSettingRepository
) {
    // Папка, где будут лежать картинки (в корне проекта сервера)
    private val uploadDir = "uploads/settings"

    fun getAllSettings(): Map<String, String?> {
        return repository.findAll().associate { it.key to it.value }
    }

    // --- ДОБАВЛЕННЫЙ МЕТОД (Исправляет ошибку в PublicController) ---
    fun getSettingValue(key: String): String? {
        return repository.findById(key).map { it.value }.orElse(null)
    }
    // ----------------------------------------------------------------

    fun uploadSettingImage(key: String, file: MultipartFile): String {
        // 1. Создаем папку, если нет
        val directory = File(uploadDir)
        if (!directory.exists()) {
            directory.mkdirs()
        }

        // 2. Генерируем имя файла (key.png или key.jpg)
        val extension = file.originalFilename?.substringAfterLast(".", "png") ?: "png"
        val fileName = "$key.$extension"
        val filePath = Paths.get(uploadDir, fileName)

        // 3. Сохраняем файл
        Files.copy(file.inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)

        // 4. Формируем URL
        // ВАЖНО: При деплое замени localhost на реальный домен или IP сервера!
        val fileUrl = "http://localhost:8080/uploads/settings/$fileName"

        // 5. Сохраняем в БД
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