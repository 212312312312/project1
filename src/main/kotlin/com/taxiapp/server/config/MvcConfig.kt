package com.taxiapp.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class MvcConfig(
    @Value("\${file.upload-dir}") private val uploadDir: String
) : WebMvcConfigurer {

    /**
     * Настраивает "раздачу" статичных файлов (наших картинок).
     *
     * Любой запрос к /images/filename.png
     * будет перенаправлен на C:/dev/server-uploads/filename.png
     */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // "file:" - это важно для Windows
        val resourceLocation = "file:$uploadDir/"
        
        registry.addResourceHandler("/images/**")
            .addResourceLocations(resourceLocation)
    }
}