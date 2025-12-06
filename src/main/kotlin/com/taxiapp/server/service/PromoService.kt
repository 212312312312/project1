package com.taxiapp.server.service

import com.taxiapp.server.model.promo.ClientPromoProgress
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.PromoTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PromoService(
    private val promoTaskRepository: PromoTaskRepository,
    private val progressRepository: ClientPromoProgressRepository
) {

    // 1. Зараховуємо поїздку (+1 до прогресу)
    @Transactional
    fun updateProgressOnRideCompletion(client: Client) {
        // Беремо всі активні завдання (наприклад "5 поїздок")
        val activeTasks = promoTaskRepository.findAllByIsActiveTrue()

        for (task in activeTasks) {
            // Знаходимо прогрес клієнта по цьому завданню або створюємо новий
            val progress = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
                .orElseGet {
                    ClientPromoProgress(client = client, promoTask = task)
                }

            // Якщо нагорода ще не отримана -> додаємо поїздку
            if (!progress.isRewardAvailable) {
                progress.currentRidesCount++

                // Перевіряємо, чи виконали умову
                if (progress.currentRidesCount >= task.requiredRides) {
                    progress.isRewardAvailable = true
                    // Можна зафіксувати лічильник на максимумі для краси
                    progress.currentRidesCount = task.requiredRides 
                }
                
                progressRepository.save(progress)
            }
        }
    }

    // 2. Знаходимо активну знижку (для створення замовлення)
    fun findActiveReward(client: Client): ClientPromoProgress? {
        // Беремо першу доступну нагороду (можна ускладнити логіку, якщо акцій багато)
        return progressRepository.findFirstByClientIdAndIsRewardAvailableTrue(client.id).orElse(null)
    }

    // 3. Списуємо знижку (після завершення поїздки зі знижкою)
    @Transactional
    fun markRewardAsUsed(client: Client) {
        val activeReward = findActiveReward(client)
        
        if (activeReward != null) {
            activeReward.isRewardAvailable = false
            activeReward.currentRidesCount = 0 // Скидаємо прогрес на 0, щоб почати нове коло
            progressRepository.save(activeReward)
        }
    }
    
    // 4. Отримати список всіх завдань клієнта (для відображення в додатку)
    fun getClientPromos(client: Client): List<ClientPromoProgress> {
        val allTasks = promoTaskRepository.findAllByIsActiveTrue()
        
        // Ми маємо повернути список прогресу для КОЖНОГО активного завдання
        // Якщо прогресу в БД ще немає, створюємо "віртуальний" об'єкт для показу (0/5)
        return allTasks.map { task ->
            progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
                .orElse(ClientPromoProgress(client = client, promoTask = task, currentRidesCount = 0))
        }
    }

    fun getActiveDiscountPercent(client: Client): Double {
        val reward = findActiveReward(client)
        return reward?.promoTask?.discountPercent ?: 0.0
    }
}