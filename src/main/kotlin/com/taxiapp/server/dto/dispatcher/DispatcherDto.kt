package com.taxiapp.server.dto.dispatcher

import com.taxiapp.server.model.user.User

// DTO для списку диспетчерів
data class DispatcherDto(
    val id: Long,
    val userLogin: String,
    val fullName: String,
    val isBlocked: Boolean
) {
    constructor(user: User) : this(
        id = user.id,
        userLogin = user.userLogin ?: "", // Диспетчер завжди має userLogin
        fullName = user.fullName,
        isBlocked = user.isBlocked
    )
}