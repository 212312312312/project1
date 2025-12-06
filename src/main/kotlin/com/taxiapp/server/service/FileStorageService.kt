package com.taxiapp.server.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Service
class FileStorageService(
    @Value("\${file.upload-dir}") private val uploadDir: String
) {
    private val rootLocation: Path = Paths.get(uploadDir)

    init {
        // Создаем папку, если ее нет
        if (Files.notExists(rootLocation)) {
            Files.createDirectories(rootLocation)
        }
    }

    /**
     * Сохраняет файл и возвращает его уникальное имя
     */
    fun store(file: MultipartFile): String {
        if (file.isEmpty) {
            throw RuntimeException("Не удалось сохранить пустой файл.")
        }
        
        // 1. Получаем расширение (напр. ".png")
        val extension = file.originalFilename?.substringAfterLast('.', "") ?: ""
        // 2. Генерируем уникальное имя
        val uniqueFilename = "${UUID.randomUUID()}.$extension"
        
        // 3. Решаем путь (C:\dev\server-uploads\xxxxx.png)
        val destinationFile = rootLocation.resolve(uniqueFilename)
            .normalize().toAbsolutePath()

        // 4. Копируем байты
        Files.copy(file.inputStream, destinationFile)
        
        // 5. Возвращаем только имя файла
        return uniqueFilename
    }

    /**
     * Загружает файл как "Ресурс" для раздачи
     */
    fun loadAsResource(filename: String): Resource {
        val file = rootLocation.resolve(filename)
        val resource = UrlResource(file.toUri())
        
        if (resource.exists() || resource.isReadable) {
            return resource
        } else {
            throw RuntimeException("Не удалось прочитать файл: $filename")
        }
    }
    
    /**
     * Удаляет старый файл (если он есть)
     */
    fun delete(filename: String?) {
        if (filename.isNullOrBlank()) return
        
        try {
            val file = rootLocation.resolve(filename)
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            // Логгируем, но не "роняем" приложение
            println("Не удалось удалить старый файл: $filename. Причина: ${e.message}")
        }
    }
}