package com.taxiapp.server.dto.driver

data class UpdateDriverStatusRequest(
    val isOnline: Boolean,   // Boolean, а не String
    val latitude: Double?,   // Могут быть null, если уходит в офлайн
    val longitude: Double?
)