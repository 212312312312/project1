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
        // Перевіряємо, чи є завдання. Якщо немає - створюємо.
        if (promoTaskRepository.count() == 0L) {
            val task1 = PromoTask(
                title = "Легкий старт",
                description = "Зроби 3 поїздки",
                requiredRides = 3,
                discountPercent = 10.0,
                isActive = true
            )

            val task2 = PromoTask(
                title = "Досвідчений",
                description = "Зроби 10 поїздок",
                requiredRides = 10,
                discountPercent = 20.0,
                isActive = true
            )

            promoTaskRepository.saveAll(listOf(task1, task2))
            println(">>> DATA INITIALIZER: Тестові завдання створено!")
        }
    }
}