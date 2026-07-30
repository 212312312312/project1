package com.taxiapp.server.dto.driver

import jakarta.validation.constraints.Min

data class TempBlockRequest(
    @field:Min(value = 1, message = "Длительность блокировки должна быть не менее 1 часа")
    val durationHours: Long
)