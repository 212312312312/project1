package com.taxiapp.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

@Configuration
class MvcConfig : WebMvcConfigurer {

    /**
     * Настройка CORS.
     */
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins("http://localhost:5173", "http://localhost:3000")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }

    /**
     * Настраиваем раздачу файлов.
     */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Получаем абсолютный путь к папке uploads в корне проекта
        // System.getProperty("user.dir") возвращает путь к корню проекта
        val uploadPath = Paths.get(System.getProperty("user.dir"), "uploads")
        val uploadUri = uploadPath.toUri().toString()

        // 1. Раздаем картинки водителей и авто
        // Ссылка: http://localhost:8080/images/car_123.jpg
        registry.addResourceHandler("/images/**")
            .addResourceLocations(uploadUri)

        // 2. Раздаем настройки (если нужно отдельно)
        // Ссылка: http://localhost:8080/uploads/settings/pic.png
        // (Опционально, если структура папок сложнее, можно оставить как есть,
        // но лучше просто использовать один handler)
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(uploadUri)
    }
}