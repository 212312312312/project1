package com.taxiapp.server.dto.news

data class CreateNewsRequest(
    val title: String,
    val content: String
)

data class NewsDto(
    val id: Long,
    val title: String,
    val content: String,
    val date: String // Будем передавать дату строкой (например "19.12.2025")
)