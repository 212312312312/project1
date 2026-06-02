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
        // ИСПРАВЛЕНО: Используем чистый относительный префикс "file: папка/" с закрывающим слэшем
        // Это гарантирует работу и на Windows, и на Linux без багов путей
        registry.addResourceHandler("/images/**")
            .addResourceLocations("file:uploads/")

        registry.addResourceHandler("/uploads/**")
            .addResourceLocations("file:uploads/")
            
        // Раздаем статику React (если фронт встроен в jar)
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
    }
}