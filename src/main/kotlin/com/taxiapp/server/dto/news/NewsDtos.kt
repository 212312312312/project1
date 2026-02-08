package com.taxiapp.server.dto.news

import com.taxiapp.server.model.news.NewsTarget

// Цей клас можна використовувати, якщо створюємо без картинки, 
// але основний метод в контролері буде приймати параметри окремо.
data class CreateNewsRequest(
    val title: String,
    val content: String,
    val target: NewsTarget = NewsTarget.ALL
)

data class NewsDto(
    val id: Long,
    val title: String,
    val content: String,
    val date: String,
    val target: NewsTarget,
    val imageUrl: String? // Додали URL картинки
)