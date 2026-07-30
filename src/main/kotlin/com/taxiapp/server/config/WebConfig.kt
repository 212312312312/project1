package com.taxiapp.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val uploadPath = Paths.get("uploads").toAbsolutePath().toUri().toString()

        registry.addResourceHandler("/images/**")
            .addResourceLocations(uploadPath)

        // 👈 Добавили раздачу папки uploads
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(uploadPath)
    }
}