package com.taxiapp.server.model.news

import jakarta.persistence.*
import java.time.LocalDateTime

enum class NewsTarget {
    CLIENT,
    DRIVER,
    ALL
}

@Entity
@Table(name = "app_news")
data class News(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val title: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val content: String,

    // Нові поля
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val target: NewsTarget = NewsTarget.ALL, // За замовчуванням всім

    @Column(name = "image_url")
    val imageUrl: String? = null, // Посилання на картинку

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)