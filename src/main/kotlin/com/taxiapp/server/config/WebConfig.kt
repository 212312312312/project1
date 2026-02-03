package com.taxiapp.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Получаем абсолютный путь к папке uploads в корне проекта
        val uploadPath = Paths.get("uploads").toAbsolutePath().toUri().toString()

        // Говорим серверу: "Если видишь ссылку /images/..., ищи файл в папке uploads"
        registry.addResourceHandler("/images/**")
            .addResourceLocations(uploadPath)
    }
}