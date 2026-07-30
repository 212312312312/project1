package com.taxiapp.server.model.enums

enum class DriverSearchMode {
    OFFLINE, // Водій офлайн
    MANUAL,  // Ефір (Вільний)
    CHAIN,   // Ланцюг (Автоприйом)
    HOME,    // Додому
    BUSY     // Зайнятий замовленням
}