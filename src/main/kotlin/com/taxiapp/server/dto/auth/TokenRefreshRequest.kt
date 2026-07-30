package com.taxiapp.server.dto.auth

data class TokenRefreshRequest(
    val refreshToken: String
)