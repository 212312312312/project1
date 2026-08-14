package com.taxiapp.server.model.enums

enum class PayoutStatus {
    PENDING, // Ожидает выплаты диспетчером
    PAID     // Выплачено (долг закрыт)
}