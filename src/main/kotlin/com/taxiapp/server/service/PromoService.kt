package com.taxiapp.server.service

import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.promo.ClientPromoProgress
import com.taxiapp.server.model.promo.PromoTask
import com.taxiapp.server.model.promo.PromoUsage // <-- Припускаємо, що цей клас існує
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.PromoCodeRepository // <-- Додано
import com.taxiapp.server.repository.PromoTaskRepository
import com.taxiapp.server.repository.PromoUsageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class PromoService(
    private val promoTaskRepository: PromoTaskRepository,
    private val progressRepository: ClientPromoProgressRepository,
    private val promoUsageRepository: PromoUsageRepository,
    private val promoCodeRepository: PromoCodeRepository // <-- Додано репозиторій кодів
) {

    // --- ЛОГІКА АКТИВАЦІЇ ПРОМОКОДУ ---
    @Transactional
    fun activatePromoCode(client: Client, codeString: String) {
        // 1. Шукаємо код
        val promoCode = promoCodeRepository.findByCode(codeString)
            .orElseThrow { RuntimeException("Промокод не знайдено") }

        // 2. Валідація самого коду
        if (promoCode.expiresAt != null && LocalDateTime.now().isAfter(promoCode.expiresAt)) {
            throw RuntimeException("Термін дії промокоду вичерпано")
        }
        if (promoCode.usageLimit != null && promoCode.usedCount >= promoCode.usageLimit) {
            throw RuntimeException("Ліміт використань цього коду вичерпано")
        }

        // 3. Перевіряємо, чи клієнт вже використовував цей код (або має його активним)
        // Припускаємо, що в PromoUsageRepository є метод або ми перевіряємо вручну:
        val existingUsage = promoUsageRepository.findAllByClient(client)
            .any { it.promoCode.id == promoCode.id }
        
        if (existingUsage) {
            throw RuntimeException("Ви вже активували цей промокод")
        }

        // 4. Створюємо використання (знижка стає доступною)
        val usage = PromoUsage(
            client = client,
            promoCode = promoCode,
            isUsed = false,
            createdAt = LocalDateTime.now(),
            expiresAt = promoCode.activationDurationHours?.let { LocalDateTime.now().plusHours(it.toLong()) }
        )
        
        promoUsageRepository.save(usage)
    }

    @Transactional
    fun updateProgressOnRideCompletion(client: Client, order: TaxiOrder? = null) { 
        // 1. Якщо поїздка була зі знижкою — прогрес не нараховуємо
        if (order != null && order.appliedDiscount > 0.0) {
            return
        }

        val activeTasks = promoTaskRepository.findAllByIsActiveTrue()

        for (task in activeTasks) {
            val progress = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
                .orElseGet {
                    ClientPromoProgress(client = client, promoTask = task)
                }
            
            // Якщо акція одноразова і вже виконана — пропускаємо
            if (task.isOneTime && progress.completedCount > 0) {
                continue 
            }

            // Якщо нагорода ще не отримана
            if (!progress.isRewardAvailable) {
                progress.currentRidesCount++

                if (progress.currentRidesCount >= task.requiredRides) {
                    // --- МОМЕНТ ЗАВЕРШЕННЯ ЗАВДАННЯ ---
                    progress.isRewardAvailable = true
                    progress.currentRidesCount = task.requiredRides 
                    
                    if (task.activeDaysDuration != null && task.activeDaysDuration!! > 0) {
                        progress.rewardExpiresAt = LocalDateTime.now().plusDays(task.activeDaysDuration!!.toLong())
                    } else {
                        progress.rewardExpiresAt = null // Безстроково
                    }
                }
                
                progressRepository.save(progress)
            }
        }
    }

    // --- ПОШУК НАЙКРАЩОЇ ЗНИЖКИ (ЗАВДАННЯ vs ПРОМОКОД) ---
    
    // 1. Знайти активну нагороду за завдання
    private fun findActiveTaskReward(client: Client): ClientPromoProgress? {
        val rewardOpt = progressRepository.findFirstByClientIdAndIsRewardAvailableTrue(client.id)
        if (rewardOpt.isPresent) {
            val reward = rewardOpt.get()
            // Перевірка терміну дії
            if (reward.rewardExpiresAt != null && LocalDateTime.now().isAfter(reward.rewardExpiresAt)) {
                // Анулюємо прострочену
                reward.isRewardAvailable = false
                progressRepository.save(reward)
                return null
            }
            return reward
        }
        return null
    }

    // 2. Знайти активний промокод
    private fun findActivePromoUsage(client: Client): PromoUsage? {
        val usages = promoUsageRepository.findAllByClientAndIsUsedFalse(client)
        // Беремо перший валідний (можна додати сортування за відсотком)
        return usages.firstOrNull { usage ->
            usage.expiresAt == null || LocalDateTime.now().isBefore(usage.expiresAt)
        }
    }

    // 3. Публічний метод для отримання відсотка знижки
    fun getActiveDiscountPercent(client: Client): Double {
        val taskReward = findActiveTaskReward(client)
        val codeReward = findActivePromoUsage(client)

        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0

        // Повертаємо найбільшу знижку
        return maxOf(taskPercent, codePercent)
    }
    
    // Метод для отримання макс. суми знижки (якщо треба в контролері)
    fun getActiveMaxDiscountAmount(client: Client): Double? {
        val taskReward = findActiveTaskReward(client)
        val codeReward = findActivePromoUsage(client)
        
        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0
        
        return if (taskPercent >= codePercent) {
            taskReward?.promoTask?.maxDiscountAmount
        } else {
            codeReward?.promoCode?.maxDiscountAmount
        }
    }

    @Transactional
    fun markRewardAsUsed(client: Client) {
        // Ми повинні "спалити" ту знижку, яка є максимальною
        val taskReward = findActiveTaskReward(client)
        val codeReward = findActivePromoUsage(client)

        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0

        if (taskPercent > 0 || codePercent > 0) {
            if (taskPercent >= codePercent) {
                // Використовуємо завдання
                taskReward?.let {
                    it.isRewardAvailable = false
                    it.currentRidesCount = 0 
                    it.completedCount += 1
                    it.rewardExpiresAt = null
                    progressRepository.save(it)
                }
            } else {
                // Використовуємо промокод
                codeReward?.let {
                    it.isUsed = true
                    it.usedAt = LocalDateTime.now() // Якщо є таке поле
                    promoUsageRepository.save(it)
                    
                    // Збільшуємо лічильник використання в самому коді
                    val code = it.promoCode
                    code.usedCount++
                    promoCodeRepository.save(code)
                }
            }
        }
    }

    // Отримання списку для програми клієнта (Ваш старий код адаптований)
    fun getClientPromos(client: Client): List<ClientPromoProgress> {
        val allTasks = promoTaskRepository.findAllByIsActiveTrue()
        
        val taskProgresses = allTasks.mapNotNull { task ->
            val progress = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
                .orElse(ClientPromoProgress(client = client, promoTask = task, currentRidesCount = 0))

            if (task.isOneTime && progress.completedCount > 0) {
                return@mapNotNull null
            }
            
            // Lazy Cleanup
            if (progress.isRewardAvailable && 
                progress.rewardExpiresAt != null && 
                LocalDateTime.now().isAfter(progress.rewardExpiresAt)) {
                
                progress.isRewardAvailable = false
                progress.rewardExpiresAt = null
                progressRepository.save(progress)
            }

            progress
        }.toMutableList()

        // Текстові промокоди
        val activeUsages = promoUsageRepository.findAllByClientAndIsUsedFalse(client)
        val usageProgresses = activeUsages.mapNotNull { usage ->
            if (usage.expiresAt != null && LocalDateTime.now().isAfter(usage.expiresAt)) {
                return@mapNotNull null
            }
            // Створюємо "фейкове" завдання для відображення в списку
            val fakeTask = PromoTask(
                id = -usage.id, // Мінусовий ID, щоб не конфліктувати
                title = usage.promoCode.code, 
                description = "Промокод: ${usage.promoCode.discountPercent.toInt()}%",
                discountPercent = usage.promoCode.discountPercent,
                requiredRides = 0,
                isActive = true,
                isOneTime = true,
                maxDiscountAmount = usage.promoCode.maxDiscountAmount
            )
            ClientPromoProgress(
                id = -usage.id,
                client = client,
                promoTask = fakeTask,
                currentRidesCount = 0,
                isRewardAvailable = true,
                rewardExpiresAt = usage.expiresAt 
            )
        }
        taskProgresses.addAll(usageProgresses)
        
        return taskProgresses
    }
    
    // Для сумісності, якщо старий метод десь викликається напряму
    fun findActiveReward(client: Client): ClientPromoProgress? {
        return findActiveTaskReward(client)
    }
}