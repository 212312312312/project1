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
            .allowedOrigins("http://localhost:5173", "http://localhost:3000") // Разрешаем фронтенд
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }

    /**
     * Настраиваем раздачу файлов.
     */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Получаем абсолютный путь к папке uploads в корне проекта
        val uploadPath = Paths.get(System.getProperty("user.dir"), "uploads")
        val uploadUri = uploadPath.toUri().toString()

        // 1. Раздаем картинки водителей и авто по пути /images/...
        // Ссылка: http://localhost:8080/images/car_123.jpg
        registry.addResourceHandler("/images/**")
            .addResourceLocations(uploadUri)

        // 2. Раздаем файлы по пути /uploads/... (для совместимости)
        // Ссылка: http://localhost:8080/uploads/settings/pic.png
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(uploadUri)
            
        // 3. Раздаем статику React (если фронт встроен в jar)
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
    }
}