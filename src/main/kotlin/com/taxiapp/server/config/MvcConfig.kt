package com.taxiapp.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class MvcConfig : WebMvcConfigurer {

    @Value("\${app.cors.allowed-origins:https://admin.unitua.com,http://localhost:5173,http://localhost:3000}")
    private lateinit var allowedOriginsStr: String

    override fun addCorsMappings(registry: CorsRegistry) {
        val origins = allowedOriginsStr.split(",").map { it.trim() }.toTypedArray()
        
        registry.addMapping("/**")
            .allowedOrigins(*origins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val cachePeriod = java.time.Duration.ofDays(7)

        registry.addResourceHandler("/images/**")
            .addResourceLocations("file:uploads/")
            .setCacheControl(org.springframework.http.CacheControl.maxAge(cachePeriod))

        registry.addResourceHandler("/uploads/**")
            .addResourceLocations("file:uploads/")
            .setCacheControl(org.springframework.http.CacheControl.maxAge(cachePeriod))
            
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
    }
}