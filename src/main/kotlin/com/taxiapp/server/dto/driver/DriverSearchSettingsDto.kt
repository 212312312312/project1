package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.enums.DriverSearchMode

data class DriverSearchSettingsDto(
    val mode: DriverSearchMode,
    val radius: Double,
    val homeSectorIds: List<Long>? // Був homeSectorId, став List
)

data class DriverSearchStateDto(
    val mode: DriverSearchMode,
    val radius: Double,
    val homeSectorIds: List<Long>?,
    val homeSectorNames: String?, // Повернемо рядок назв через кому
    val homeRidesLeft: Int
)