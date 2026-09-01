package com.taxiapp.server.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import net.coobird.thumbnailator.Thumbnails
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

@Service
class FileStorageService {

    // Считываем URL сервера из application.properties (по умолчанию http://localhost:8080)
    @Value("\${app.server.url:http://localhost:8080}")
    private lateinit var serverUrl: String

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

    fun storeIconAsWebp(file: MultipartFile, targetWidth: Int = 400, targetHeight: Int = 400): String {
        if (file.isEmpty) throw RuntimeException("Файл порожній.")

        val originalImage = ImageIO.read(file.inputStream)
            ?: throw RuntimeException("Неможливо декодувати зображення.")

        // 1. Авто-обрезка прозрачных полей (Auto-trim)
        val trimmedImage = trimTransparentBorders(originalImage)

        // 2. Пропорциональное масштабирование в bounding box (targetWidth x targetHeight)
        val scaledImage = Thumbnails.of(trimmedImage)
            .size(targetWidth, targetHeight)
            .keepAspectRatio(true)
            .asBufferedImage()

        // 3. Сохранение в формате .webp
        val uuid = UUID.randomUUID().toString()
        val webpFilename = "$uuid.webp"
        val destinationFile = rootLocation.resolve(webpFilename).normalize().toAbsolutePath()

        val webpWritten = ImageIO.write(scaledImage, "webp", destinationFile.toFile())
        if (!webpWritten) {
            // Fallback на PNG, если плагин не зарегистрирован в runtime
            val pngFilename = "$uuid.png"
            val fallbackFile = rootLocation.resolve(pngFilename).normalize().toAbsolutePath()
            ImageIO.write(scaledImage, "png", fallbackFile.toFile())
            return pngFilename
        }

        return webpFilename
    }

    fun storeBytes(bytes: ByteArray, fileName: String): String {
        val uploadDir = java.nio.file.Paths.get("uploads")
        if (!java.nio.file.Files.exists(uploadDir)) {
            java.nio.file.Files.createDirectories(uploadDir)
        }
        val targetPath = uploadDir.resolve(fileName)
        java.nio.file.Files.write(targetPath, bytes)
        return "/uploads/$fileName"
    }

    /**
     * Алгоритм сканирования альфа-канала и обрезки прозрачных полей
     */
    private fun trimTransparentBorders(img: BufferedImage): BufferedImage {
        val width = img.width
        val height = img.height

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = img.getRGB(x, y)
                val alpha = (pixel shr 24) and 0xFF
                // Порог альфы > 10 (не полностью прозрачный пиксель)
                if (alpha > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // Если картинка полностью прозрачная, возвращаем оригинал
        if (minX >= maxX || minY >= maxY) return img

        val trimmedWidth = (maxX - minX) + 1
        val trimmedHeight = (maxY - minY) + 1

        return img.getSubimage(minX, minY, trimmedWidth, trimmedHeight)
    }

    /**
     * Сохраняет файл и возвращает его уникальное имя
     * ВАЖНО: Метод называется storeFile, чтобы совпадать с DriverAppController
     */
    fun storeFile(file: MultipartFile): String {
        if (file.isEmpty) {
            throw RuntimeException("Не вдалося зберегти порожній файл.")
        }
        
        val originalFilename = file.originalFilename ?: "unknown.jpg"
        val extension = if (originalFilename.contains(".")) {
            originalFilename.substringAfterLast('.')
        } else {
            "jpg"
        }

        val uuid = UUID.randomUUID().toString()
        val uniqueFilename = "$uuid.$extension"
        val thumbFilename = "thumb_$uuid.jpg" // Легкое превью
        
        val destinationFile = rootLocation.resolve(uniqueFilename).normalize().toAbsolutePath()
        val thumbDestinationFile = rootLocation.resolve(thumbFilename).normalize().toAbsolutePath()

        try {
            // 1. Сохраняем оригинал
            Files.copy(file.inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING)
            
            // 2. Генерируем сжатую миниатюру (макс 600px по ширине/высоте, качество 75%)
            Thumbnails.of(destinationFile.toFile())
                .size(450, 450)
                .outputQuality(0.65)
                .toFile(thumbDestinationFile.toFile())

        } catch (e: Exception) {
            // Если сжатие не удалось (например, не картинка), просто копируем оригинал как превью
            try {
                Files.copy(destinationFile, thumbDestinationFile, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {}
        }
        
        return uniqueFilename
    }

    // --- ALIAS ДЛЯ СОВМЕСТИМОСТИ С NewsService ---
    fun saveFile(file: MultipartFile): String {
        return storeFile(file)
    }
    // ---------------------------------------------

    /**
     * Формирует полный абсолютный URL для отдачи внешним клиентам (React / Mobile Apps)
     */
    fun buildFullUrl(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        if (filename.startsWith("http://") || filename.startsWith("https://") || filename.startsWith("data:")) {
            return filename
        }

        val cleanFilename = filename.trimStart('/')
        val path = if (cleanFilename.startsWith("uploads/") || cleanFilename.startsWith("images/")) {
            cleanFilename
        } else {
            "uploads/$cleanFilename"
        }
        return "/$path"
    }

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
    fun loadOrGenerateResource(filename: String): Resource {
        try {
            val cleanFilename = filename.trimStart('/')
            val file = rootLocation.resolve(cleanFilename).normalize()
            
            // 1. Если файл превью или оригинал уже есть на диске — отдаем его
            if (Files.exists(file) && Files.isReadable(file)) {
                return UrlResource(file.toUri())
            }

            // 2. Если запросили thumb_, а его нет на диске
            if (cleanFilename.startsWith("thumb_")) {
                val originalFilename = cleanFilename.substringAfter("thumb_")
                val originalFile = rootLocation.resolve(originalFilename).normalize()

                if (Files.exists(originalFile) && Files.isReadable(originalFile)) {
                    return try {
                        // Попытка сжатия
                        Thumbnails.of(originalFile.toFile())
                            .size(400, 400)
                            .outputQuality(0.60)
                            .toFile(file.toFile())

                        UrlResource(file.toUri())
                    } catch (e: Throwable) {
                        // Если памяти не хватило или сбой — БЕЗОПАСНО отдаем оригинал!
                        println("Не вдалося згенерувати thumb для $originalFilename: ${e.message}")
                        UrlResource(originalFile.toUri())
                    }
                }
            }

            throw RuntimeException("Файл не знайдено: $filename")
        } catch (e: Exception) {
            throw RuntimeException("Помилка читання файлу: $filename", e)
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