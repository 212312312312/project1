package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank

data class GoogleAuthRequest(
    @field:NotBlank(message = "ID Token обов'язковий")
    val idToken: String,

    // Маркетингове джерело (наприклад: fb_ad_poznyaki)
    val acquisitionSource: String? = null
)