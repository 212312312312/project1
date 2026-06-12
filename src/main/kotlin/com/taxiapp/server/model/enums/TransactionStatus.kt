package com.taxiapp.server.model.enums

enum class TransactionStatus {
    PENDING,    // Ожидает обработки/очереди (отображается в "Ваші кошти")
    COMPLETED   // Завершено успешно
}