package com.taxiapp.server.dto.auth

data class LoginResponse(
    val token: String,
    val userId: Long,
    val refreshToken: String,
    val phoneNumber: String,
    val fullName: String,
    val role: String,
    val isNewUser: Boolean,
    val isPendingDeletion: Boolean = false
)