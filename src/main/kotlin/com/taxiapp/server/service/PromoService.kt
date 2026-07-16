package com.taxiapp.server.service

import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.promo.ClientPromoProgress
import com.taxiapp.server.model.promo.PromoTask
import com.taxiapp.server.model.promo.PromoUsage
import com.taxiapp.server.model.promo.PromoPlan
import com.taxiapp.server.model.promo.ClientPromoPlanUsage
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.PromoCodeRepository
import com.taxiapp.server.repository.PromoTaskRepository
import com.taxiapp.server.repository.PromoUsageRepository
import com.taxiapp.server.repository.ClientRepository
import com.taxiapp.server.repository.PromoPlanRepository
import com.taxiapp.server.repository.ClientPromoPlanUsageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class PromoService(
    private val promoTaskRepository: PromoTaskRepository,
    private val progressRepository: ClientPromoProgressRepository,
    private val promoUsageRepository: PromoUsageRepository,
    private val promoCodeRepository: PromoCodeRepository,
    private val promoPlanRepository: PromoPlanRepository,
    private val clientPromoPlanUsageRepository: ClientPromoPlanUsageRepository,
    private val clientRepository: ClientRepository // <- ДОБАВЛЕНО ТУТ
) {

    @Transactional
    fun activatePromoCode(client: Client, codeString: String) {
        val promoCode = promoCodeRepository.findByCode(codeString)
            .orElseThrow { RuntimeException("Промокод не знайдено") }

        if (promoCode.expiresAt != null && LocalDateTime.now().isAfter(promoCode.expiresAt)) {
            throw RuntimeException("Термін дії промокоду вичерпано")
        }
        if (promoCode.usageLimit != null && promoCode.usedCount >= promoCode.usageLimit) {
            throw RuntimeException("Ліміт використань цього коду вичерпано")
        }

        val existingUsage = promoUsageRepository.findAllByClient(client)
            .any { it.promoCode.id == promoCode.id }
        
        if (existingUsage) {
            throw RuntimeException("Ви вже активували цей промокод")
        }

        val usage = PromoUsage(
            client = client,
            promoCode = promoCode,
            isUsed = false,
            createdAt = LocalDateTime.now(),
            expiresAt = promoCode.activationDurationHours?.let { LocalDateTime.now().plusHours(it.toLong()) }
        )
        
        promoUsageRepository.save(usage)
    }

    fun findActiveFreeMinPlan(client: Client): PromoPlan? {
        val now = LocalDateTime.now()
        val plan = promoPlanRepository.findFirstByIsActiveTrueAndStartDateBeforeAndEndDateAfter(now, now).orElse(null) ?: return null
        val alreadyUsed = clientPromoPlanUsageRepository.existsByClientAndPromoPlanId(client, plan.id)
        if (alreadyUsed) return null
        
        // НОВОЕ: проверка глобального лимита плана
        if (plan.maxUses != null) {
            // Предполагаем, что в clientPromoPlanUsageRepository есть стандартный countByPromoPlanId
            val currentUsages = clientPromoPlanUsageRepository.countByPromoPlanId(plan.id)
            if (currentUsages >= plan.maxUses!!) {
                return null
            }
        }
        
        return plan
    }

    @Transactional
    fun markPromoPlanAsUsed(client: Client, planId: Long) {
        val plan = promoPlanRepository.findById(planId).orElse(null) ?: return
        if (!clientPromoPlanUsageRepository.existsByClientAndPromoPlanId(client, planId)) {
            clientPromoPlanUsageRepository.save(ClientPromoPlanUsage(client = client, promoPlan = plan))
        }
    }

    @Transactional
    fun updateProgressOnRideCompletion(client: Client, order: TaxiOrder? = null) { 
        if (order != null && order.appliedDiscount > 0.0) {
            return
        }

        val activeTasks = promoTaskRepository.findAllByIsActiveTrue()

        for (task in activeTasks) {
            val progressOpt = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
            
            if (!progressOpt.isPresent && task.maxAllocations != null) {
                val alreadyAllocated = progressRepository.countByPromoTaskId(task.id)
                val slotsLeft = task.maxAllocations!! - alreadyAllocated // <- ИСПРАВЛЕНО НА !!
                if (slotsLeft <= 0) continue
                
                val eligibleIds = clientRepository.findTopEligibleClientIdsForTask(task.id, slotsLeft)
                if (!eligibleIds.contains(client.id)) continue
            }
            val progress = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
                .orElseGet {
                    ClientPromoProgress(client = client, promoTask = task)
                }
            
            if (task.isOneTime && progress.completedCount > 0) {
                continue 
            }

            if (!progress.isRewardAvailable) {
                progress.currentRidesCount++

                if (progress.currentRidesCount >= task.requiredRides) {
                    progress.isRewardAvailable = true
                    progress.currentRidesCount = task.requiredRides 
                    
                    if (task.activeDaysDuration != null && task.activeDaysDuration!! > 0) {
                        progress.rewardExpiresAt = LocalDateTime.now().plusDays(task.activeDaysDuration!!.toLong())
                    } else {
                        progress.rewardExpiresAt = null
                    }
                }
                
                progressRepository.save(progress)
            }
        }
    }

    private fun findActiveTaskReward(client: Client): ClientPromoProgress? {
        val rewardOpt = progressRepository.findFirstByClientIdAndIsRewardAvailableTrue(client.id)
        if (rewardOpt.isPresent) {
            val reward = rewardOpt.get()
            if (reward.rewardExpiresAt != null && LocalDateTime.now().isAfter(reward.rewardExpiresAt)) {
                reward.isRewardAvailable = false
                progressRepository.save(reward)
                return null
            }
            return reward
        }
        return null
    }

    private fun findActivePromoUsage(client: Client): PromoUsage? {
        val usages = promoUsageRepository.findAllByClientAndIsUsedFalse(client)
        return usages.firstOrNull { usage ->
            usage.expiresAt == null || LocalDateTime.now().isBefore(usage.expiresAt)
        }
    }

    fun getActiveDiscountPercent(client: Client): Double {
        val taskReward = findActiveTaskReward(client)
        val codeReward = findActivePromoUsage(client)

        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0

        return maxOf(taskPercent, codePercent)
    }
    
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
        val taskReward = findActiveTaskReward(client)
        val codeReward = findActivePromoUsage(client)

        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0

        if (taskPercent > 0 || codePercent > 0) {
            if (taskPercent >= codePercent) {
                taskReward?.let {
                    it.isRewardAvailable = false
                    it.currentRidesCount = 0 
                    it.completedCount += 1
                    it.rewardExpiresAt = null
                    progressRepository.save(it)
                }
            } else {
                codeReward?.let {
                    it.isUsed = true
                    promoUsageRepository.save(it)
                    
                    val code = it.promoCode
                    code.usedCount++
                    promoCodeRepository.save(code)
                }
            }
        }
    }

    fun getClientPromos(client: Client): List<ClientPromoProgress> {
        val allTasks = promoTaskRepository.findAllByIsActiveTrue()
        
        val taskProgresses = allTasks.mapNotNull { task ->
            val progressOpt = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)
            
            val progress = if (progressOpt.isPresent) {
                progressOpt.get()
            } else {
                // НОВОЕ: Проверка лимита распределения по приоритетам для новых участников
                if (task.maxAllocations != null) {
                    val alreadyAllocated = progressRepository.countByPromoTaskId(task.id)
                    val slotsLeft = task.maxAllocations!! - alreadyAllocated // <- ИСПРАВЛЕНО НА !!
                    if (slotsLeft <= 0) return@mapNotNull null
                    
                    // Проверяем, входит ли текущий клиент в топ по приоритетам (ОЧИЩЕНО ОТ .let)
                    val eligibleIds = clientRepository.findTopEligibleClientIdsForTask(task.id, slotsLeft)
                    if (!eligibleIds.contains(client.id)) return@mapNotNull null
                }
                ClientPromoProgress(client = client, promoTask = task, currentRidesCount = 0)
            }

            if (task.isOneTime && progress.completedCount > 0) {
                return@mapNotNull null
            }
            
            if (progress.isRewardAvailable && 
                progress.rewardExpiresAt != null && 
                LocalDateTime.now().isAfter(progress.rewardExpiresAt)) {
                
                progress.isRewardAvailable = false
                progress.rewardExpiresAt = null
                progressRepository.save(progress)
            }

            progress
        }.toMutableList()

        val activeUsages = promoUsageRepository.findAllByClientAndIsUsedFalse(client)
        val usageProgresses = activeUsages.mapNotNull { usage ->
            if (usage.expiresAt != null && LocalDateTime.now().isAfter(usage.expiresAt)) {
                return@mapNotNull null
            }
            val fakeTask = PromoTask(
                id = -usage.id,
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
    
    fun findActiveReward(client: Client): ClientPromoProgress? {
        return findActiveTaskReward(client)
    }
}