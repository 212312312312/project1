package com.taxiapp.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class MvcConfig(
    // 1. Вернули чтение пути из настроек для старых картинок
    @Value("\${file.upload-dir}") private val uploadDir: String
) : WebMvcConfigurer {

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
     * Теперь у нас ДВА пути.
     */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        
        // 1. СТАРАЯ ЛОГИКА (Для водителей и тарифов)
        // Ссылка: http://localhost:8080/images/pic.png
        // Путь на диске: Тот, что в application.properties (file.upload-dir)
        registry.addResourceHandler("/images/**")
            .addResourceLocations("file:$uploadDir/")

        // 2. НОВАЯ ЛОГИКА (Для настроек)
        // Ссылка: http://localhost:8080/uploads/settings/pic.png
        // Путь на диске: папка uploads в корне проекта
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations("file:uploads/")
    }
}