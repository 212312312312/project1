package com.taxiapp.server.service

import org.springframework.stereotype.Service

@Service
class OrderScheduler {
    // Автоматичне скасування замовлень за таймаутом повністю вимкнено.
    // Замовлення залишаються в активному пошуку (в ефірі) доти, доки водій не прийме виклик або клієнт не скасує його власноруч.
}