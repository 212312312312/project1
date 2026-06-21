package com.taxiapp.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import com.fasterxml.jackson.databind.ObjectMapper

@Configuration
class RedisConfig {

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory, objectMapper: ObjectMapper): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.connectionFactory = connectionFactory
        
        val stringSerializer = StringRedisSerializer()
        val jsonSerializer = Jackson2JsonRedisSerializer(Any::class.java)
        
        template.keySerializer = stringSerializer
        template.hashKeySerializer = stringSerializer
        template.valueSerializer = jsonSerializer
        template.hashValueSerializer = jsonSerializer
        
        return template
    }
}