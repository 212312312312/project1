package com.taxiapp.server.dto.driver

data class DriverNotificationDto(
    val id: Long,
    val title: String,
    val body: String,
    val type: String,
    val date: String, // Строка времени (HH:mm dd.MM)
    val isRead: Boolean
)