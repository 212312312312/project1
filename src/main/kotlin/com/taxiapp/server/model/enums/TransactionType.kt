package com.taxiapp.server.model.enums

enum class TransactionType {
    DEPOSIT,        // Пополнение
    WITHDRAWAL,     // Вывод средств
    COMMISSION,     // Списание комиссии за заказ
    PENALTY,        // Штраф
    BONUS           // Бонус от компании
}