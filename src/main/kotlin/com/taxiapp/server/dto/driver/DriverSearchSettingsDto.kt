package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.enums.DriverSearchMode

// REQUEST: То, что присылает приложение (только настройки)
data class DriverSearchSettingsDto(
    val mode: DriverSearchMode,
    val radius: Double,
    val homeSectorIds: List<Long>?
)

// RESPONSE: То, что мы отдаем приложению (состояние + названия + лимиты)
data class DriverSearchStateDto(
    val mode: DriverSearchMode,
    val radius: Double,
    val homeSectorIds: List<Long>?,
    val homeSectorNames: String?, // Названия секторов текстом
    val homeRidesLeft: Int        // Остаток поездок
)