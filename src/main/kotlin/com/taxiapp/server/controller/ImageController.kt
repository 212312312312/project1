package com.taxiapp.server.controller

import com.taxiapp.server.service.FileStorageService
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/images")
// 👈 УБРАЛИ @CrossOrigin(origins = ["*"]), так как CORS уже настроен в SecurityConfig!
class ImageController(
    private val fileStorageService: FileStorageService
) {

    @GetMapping("/{filename:.+}")
    fun getImage(@PathVariable filename: String): ResponseEntity<Resource> {
        return try {
            val resource = fileStorageService.loadOrGenerateResource(filename)
            
            val mediaType = when {
                filename.endsWith(".png", true) -> MediaType.IMAGE_PNG
                filename.endsWith(".gif", true) -> MediaType.IMAGE_GIF
                filename.endsWith(".webp", true) -> MediaType.parseMediaType("image/webp")
                else -> MediaType.IMAGE_JPEG
            }

            ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${resource.filename}\"")
                .body(resource)
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }
    }
}