package com.taxiapp.server.dto.auth

data class LoginResponse(
    val token: String,
    val userId: Long,
    val phoneNumber: String,
    val fullName: String,
    val role: String,
    val isNewUser: Boolean
)