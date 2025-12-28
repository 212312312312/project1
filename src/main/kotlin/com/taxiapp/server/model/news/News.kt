package com.taxiapp.server.model.news

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "app_news") // Называем таблицу app_news, чтобы избежать конфликтов с зарезервированным словом news в некоторых БД
data class News(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val title: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val content: String,

    // Дата создается автоматически при создании объекта
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)