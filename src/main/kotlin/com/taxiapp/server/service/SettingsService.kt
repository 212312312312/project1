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
    // Папка для сохранения картинок
    private val uploadDir = "uploads/settings"

    fun getAllSettings(): Map<String, String?> {
        return repository.findAll().associate { it.key to it.value }
    }

    // Этот метод нужен для PublicController
    fun getSettingValue(key: String): String? {
        return repository.findById(key).map { it.value }.orElse(null)
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

        // URL (в реальном продакшене тут нужен домен)
        val fileUrl = "http://localhost:8080/uploads/settings/$fileName"

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