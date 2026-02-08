package com.taxiapp.server.service

import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

@Service
class FileStorageService {

    // Жестко привязываем путь к папке uploads в корне проекта
    private val rootLocation: Path = Paths.get(System.getProperty("user.dir"), "uploads")

    init {
        try {
            if (Files.notExists(rootLocation)) {
                Files.createDirectories(rootLocation)
            }
        } catch (e: Exception) {
            throw RuntimeException("Не удалось создать директорию для загрузки файлов!", e)
        }
    }

    /**
     * Сохраняет файл и возвращает его уникальное имя
     * ВАЖНО: Метод называется storeFile, чтобы совпадать с DriverAppController
     */
    fun storeFile(file: MultipartFile): String {
        if (file.isEmpty) {
            throw RuntimeException("Не удалось сохранить пустой файл.")
        }
        
        // 1. Получаем расширение (напр. "png")
        val originalFilename = file.originalFilename ?: "unknown.jpg"
        val extension = if (originalFilename.contains(".")) {
            originalFilename.substringAfterLast('.')
        } else {
            "jpg"
        }

        // 2. Генерируем уникальное имя
        val uniqueFilename = "${UUID.randomUUID()}.$extension"
        
        // 3. Решаем путь
        val destinationFile = rootLocation.resolve(uniqueFilename)
            .normalize().toAbsolutePath()

        // 4. Копируем байты
        try {
            Files.copy(file.inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw RuntimeException("Ошибка сохранения файла: ${e.message}", e)
        }
        
        // 5. Возвращаем только имя файла
        return uniqueFilename
    }

    // --- ALIAS ДЛЯ СОВМЕСТИМОСТИ С NewsService ---
    fun saveFile(file: MultipartFile): String {
        return storeFile(file)
    }
    // ---------------------------------------------

    /**
     * Загружает файл как "Ресурс" для раздачи
     */
    fun loadAsResource(filename: String): Resource {
        try {
            val file = rootLocation.resolve(filename)
            val resource = UrlResource(file.toUri())
            
            if (resource.exists() || resource.isReadable) {
                return resource
            } else {
                throw RuntimeException("Не удалось прочитать файл: $filename")
            }
        } catch (e: Exception) {
            throw RuntimeException("Не удалось прочитать файл: $filename", e)
        }
    }
    
    /**
     * Удаляет старый файл
     */
    fun delete(filename: String?) {
        if (filename.isNullOrBlank()) return
        
        try {
            val file = rootLocation.resolve(filename)
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            println("Не удалось удалить старый файл: $filename. Причина: ${e.message}")
        }
    }
}