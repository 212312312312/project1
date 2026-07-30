package com.taxiapp.server.config

import com.taxiapp.server.model.promo.PromoTask
import com.taxiapp.server.repository.PromoTaskRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val promoTaskRepository: PromoTaskRepository
) : CommandLineRunner {

   override fun run(vararg args: String?) {
        // Автосоздание дефолтных заданий отключено для защиты бюджета.
        // Все акции создаются вручную через веб-диспетчерскую.
    }
}