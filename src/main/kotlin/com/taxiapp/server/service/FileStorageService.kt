package com.taxiapp.server.service

import net.coobird.thumbnailator.Thumbnails
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO

@Service
class FileStorageService {

    @Value("\${app.server.url:http://localhost:8080}")
    private lateinit var serverUrl: String

    // Жестко привязываем путь к папке uploads в корне проекта (нормализованный абсолютный путь)
    private val rootLocation: Path = Paths.get(System.getProperty("user.dir"), "uploads").toAbsolutePath().normalize()

    // ЗАЩИТА: SVG, HTML, JS строго запрещены для защиты от Stored XSS!
    private val allowedImageExtensions = setOf("jpg", "jpeg", "png", "webp")

    init {
        try {
            if (Files.notExists(rootLocation)) {
                Files.createDirectories(rootLocation)
            }
        } catch (e: Exception) {
            throw RuntimeException("Не удалось создать директорию для загрузки файлов!", e)
        }
    }

    // Централизованная защита от Path Traversal (../)
    private fun resolveSafePath(filename: String): Path {
        val cleanName = Paths.get(filename).fileName.toString()
        val resolved = rootLocation.resolve(cleanName).normalize().toAbsolutePath()
        if (!resolved.startsWith(rootLocation)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустиме ім'я файлу")
        }
        return resolved
    }

    fun storeIconAsWebp(file: MultipartFile, targetWidth: Int = 400, targetHeight: Int = 400): String {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл порожній.")

        val originalImage = ImageIO.read(file.inputStream)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Неможливо декодувати зображення.")

        // Защита от Pixel Flood / Decompression Bomb OOM
        if (originalImage.width > 8000 || originalImage.height > 8000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Зображення занадто велике")
        }

        val trimmedImage = trimTransparentBorders(originalImage)

        val scaledImage = Thumbnails.of(trimmedImage)
            .size(targetWidth, targetHeight)
            .keepAspectRatio(true)
            .asBufferedImage()

        val uuid = UUID.randomUUID().toString()
        val webpFilename = "$uuid.webp"
        val destinationFile = resolveSafePath(webpFilename)

        val webpWritten = ImageIO.write(scaledImage, "webp", destinationFile.toFile())
        if (!webpWritten) {
            val pngFilename = "$uuid.png"
            val fallbackFile = resolveSafePath(pngFilename)
            ImageIO.write(scaledImage, "png", fallbackFile.toFile())
            return pngFilename
        }

        return webpFilename
    }

    fun storeBytes(bytes: ByteArray, fileName: String): String {
        val targetPath = resolveSafePath(fileName)
        Files.write(targetPath, bytes)
        return "/uploads/${targetPath.fileName}"
    }

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
                if (alpha > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (minX >= maxX || minY >= maxY) return img

        val trimmedWidth = (maxX - minX) + 1
        val trimmedHeight = (maxY - minY) + 1

        return img.getSubimage(minX, minY, trimmedWidth, trimmedHeight)
    }

    fun storeFile(file: MultipartFile): String {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не вдалося зберегти порожній файл.")
        }

        val originalFilename = file.originalFilename ?: "unknown.jpg"
        val extension = originalFilename.substringAfterLast('.', "jpg").lowercase()

        if (extension !in allowedImageExtensions) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Недопустимий формат файлу (.$extension). Дозволені лише: JPG, PNG, WEBP."
            )
        }

        val uuid = UUID.randomUUID().toString()
        val uniqueFilename = "$uuid.$extension"
        val thumbFilename = "thumb_$uuid.jpg"

        val destinationFile = resolveSafePath(uniqueFilename)
        val thumbDestinationFile = resolveSafePath(thumbFilename)

        try {
            Files.copy(file.inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING)

            Thumbnails.of(destinationFile.toFile())
                .size(450, 450)
                .outputQuality(0.65)
                .toFile(thumbDestinationFile.toFile())
        } catch (e: Exception) {
            try {
                Files.copy(destinationFile, thumbDestinationFile, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {}
        }

        return uniqueFilename
    }

    fun saveFile(file: MultipartFile): String {
        return storeFile(file)
    }

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

    fun loadAsResource(filename: String): Resource {
        val file = resolveSafePath(filename)
        val resource = UrlResource(file.toUri())

        if (resource.exists() || resource.isReadable) {
            return resource
        } else {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Не вдалося прочитати файл: $filename")
        }
    }

    fun loadOrGenerateResource(filename: String): Resource {
        val file = resolveSafePath(filename)
        val cleanFilename = file.fileName.toString()

        if (Files.exists(file) && Files.isReadable(file)) {
            return UrlResource(file.toUri())
        }

        if (cleanFilename.startsWith("thumb_")) {
            val originalFilename = cleanFilename.substringAfter("thumb_")
            val originalFile = resolveSafePath(originalFilename)

            if (Files.exists(originalFile) && Files.isReadable(originalFile)) {
                return try {
                    Thumbnails.of(originalFile.toFile())
                        .size(400, 400)
                        .outputQuality(0.60)
                        .toFile(file.toFile())

                    UrlResource(file.toUri())
                } catch (e: Throwable) {
                    UrlResource(originalFile.toUri())
                }
            }
        }

        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не знайдено: $filename")
    }

    fun delete(filename: String?) {
        if (filename.isNullOrBlank()) return

        try {
            val file = resolveSafePath(filename)
            Files.deleteIfExists(file)
        } catch (_: Exception) {}
    }
}