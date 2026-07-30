package com.taxiapp.server.dto.driver

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class UpdateDriverRequest(
    val fullName: String? = null,

    val email: String? = null,
    val rnokpp: String? = null,
    val driverLicense: String? = null,

    // Автомобіль
    val make: String? = null,
    val model: String? = null,
    val color: String? = null,
    val plateNumber: String? = null,
    val vin: String? = null,
    val year: Int? = null,
    val carType: String? = null,
    
    val tariffIds: List<Long> = emptyList(),

    // --- НОВЫЕ ПОЛЯ ДЛЯ ОБНОВЛЕНИЯ (ИНВАЛИДНОСТЬ) ---
    val hasMovementIssue: Boolean? = null,
    val hasHearingIssue: Boolean? = null,
    val isDeaf: Boolean? = null,
    val hasSpeechIssue: Boolean? = null
)