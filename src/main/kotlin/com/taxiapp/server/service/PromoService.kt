package com.taxiapp.server.service

import com.taxiapp.server.model.analytics.ClientAppAction
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.promo.ClientPromoPlanUsage
import com.taxiapp.server.model.promo.ClientPromoProgress
import com.taxiapp.server.model.promo.PromoPlan
import com.taxiapp.server.model.promo.PromoTask
import com.taxiapp.server.model.promo.PromoUsage
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientAppActionRepository
import com.taxiapp.server.repository.ClientPromoPlanUsageRepository
import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.ClientRepository
import com.taxiapp.server.repository.PromoCodeRepository
import com.taxiapp.server.repository.PromoPlanRepository
import com.taxiapp.server.repository.PromoTaskRepository
import com.taxiapp.server.repository.PromoUsageRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class PromoService(
    private val promoTaskRepository: PromoTaskRepository,
    private val progressRepository: ClientPromoProgressRepository,
    private val promoUsageRepository: PromoUsageRepository,
    private val promoCodeRepository: PromoCodeRepository,
    private val promoPlanRepository: PromoPlanRepository,
    private val clientPromoPlanUsageRepository: ClientPromoPlanUsageRepository,
    private val clientRepository: ClientRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val clientAppActionRepository: ClientAppActionRepository
) {

    @Transactional(noRollbackFor = [RuntimeException::class, ResponseStatusException::class])
    fun activatePromoCode(client: Client, codeString: String, deviceId: String? = null) {
        val code = codeString.uppercase().trim()
        val promoCode = promoCodeRepository.findByCode(code)
            .orElseThrow { RuntimeException("Промокод не знайдено") }

        if (promoCode.expiresAt != null && LocalDateTime.now().isAfter(promoCode.expiresAt)) {
            throw RuntimeException("Термін дії промокоду вичерпано")
        }

        // Антифрод проверка по deviceId (из запроса, профиля клиента или последнего заказа)
        val targetDeviceId = deviceId?.takeIf { it.isNotBlank() }
            ?: client.deviceId?.takeIf { it.isNotBlank() }
            ?: taxiOrderRepository.findLatestDeviceIdByClientId(client.id!!)

        if (!targetDeviceId.isNullOrBlank()) {
            val deviceAlreadyUsed = promoUsageRepository.existsByDeviceIdAndClientIdNot(targetDeviceId, client.id!!)
            if (deviceAlreadyUsed) {
                // Фиксируем заблокированную попытку мультиаккаунта в таблице аналитики
                clientAppActionRepository.save(
                    ClientAppAction(
                        clientId = client.id!!,
                        actionName = "promo_blocked_device",
                        actionValue = "code:$code,device:$targetDeviceId",
                        sessionId = "server_antifraud",
                        createdAt = LocalDateTime.now()
                    )
                )
                throw RuntimeException("Цей пристрій вже використовував вітальний промокод під іншим акаунтом")
            }
        }

        val totalUsages = promoUsageRepository.countByPromoCodeId(promoCode.id)
        if (promoCode.usageLimit != null && totalUsages >= promoCode.usageLimit) {
            throw RuntimeException("Ліміт використань цього коду вичерпано")
        }

        val existingUsage = promoUsageRepository.existsByClientAndPromoCodeId(client, promoCode.id)
        if (existingUsage) {
            throw RuntimeException("Ви вже активували цей промокод")
        }

        val existingActive = promoUsageRepository.findFirstByClientAndIsUsedFalseOrderByCreatedAtDesc(client)
        if (existingActive.isPresent) {
            throw RuntimeException("У вас вже є активний промокод. Використайте його спочатку.")
        }

        val usage = PromoUsage(
            client = client,
            promoCode = promoCode,
            isUsed = false,
            createdAt = LocalDateTime.now(),
            expiresAt = promoCode.activationDurationHours?.let { LocalDateTime.now().plusHours(it.toLong()) },
            deviceId = targetDeviceId,
            discountAmount = 0.0
        )

        promoUsageRepository.save(usage)
    }

    fun findActiveFreeMinPlan(client: Client): PromoPlan? {
        val now = LocalDateTime.now()
        val plan = promoPlanRepository.findFirstByPlanTypeAndIsActiveTrueAndStartDateBeforeAndEndDateAfter(
            com.taxiapp.server.model.promo.PromoPlanType.FREE_MINIMUM,
            now,
            now
        ).orElse(null) ?: return null

        val alreadyUsed = clientPromoPlanUsageRepository.existsByClientAndPromoPlanId(client, plan.id)
        if (alreadyUsed) return null

        if (plan.maxUses != null) {
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

    fun findActiveRegistrationDiscountPlan(client: Client): PromoPlan? {
        val now = LocalDateTime.now()
        val plan = promoPlanRepository.findFirstByPlanTypeAndIsActiveTrueAndStartDateBeforeAndEndDateAfter(
            com.taxiapp.server.model.promo.PromoPlanType.REGISTRATION_DISCOUNT,
            now,
            now
        ).orElse(null) ?: return null

        val clientRegisteredAt = client.createdAt ?: return null
        if (clientRegisteredAt.isBefore(plan.startDate) || clientRegisteredAt.isAfter(plan.endDate)) {
            return null
        }

        if (plan.validityHours != null && plan.validityHours!! > 0) {
            val expiresAt = clientRegisteredAt.plusHours(plan.validityHours!!.toLong())
            if (now.isAfter(expiresAt)) {
                return null
            }
        }

        val alreadyUsed = clientPromoPlanUsageRepository.existsByClientAndPromoPlanId(client, plan.id)
        if (alreadyUsed) return null

        if (plan.maxUses != null) {
            val currentUsages = clientPromoPlanUsageRepository.countByPromoPlanId(plan.id)
            if (currentUsages >= plan.maxUses!!) {
                return null
            }
        }

        return plan
    }

    @Transactional
    fun updateProgressOnRideCompletion(client: Client, order: TaxiOrder? = null) {
        if (order != null && order.appliedDiscount > 0.0) {
            return
        }

        val now = LocalDateTime.now()
        val activeTasks = promoTaskRepository.findAllByIsActiveTrue()
            .filter { it.expiresAt == null || it.expiresAt!!.isAfter(now) }

        for (task in activeTasks) {
            val progressOpt = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)

            if (!progressOpt.isPresent && task.maxAllocations != null) {
                val alreadyAllocated = progressRepository.countByPromoTaskId(task.id)
                val slotsLeft = task.maxAllocations!! - alreadyAllocated
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
        val regPlan = findActiveRegistrationDiscountPlan(client)

        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0
        val regPercent = regPlan?.discountPercent ?: 0.0

        return maxOf(taskPercent, codePercent, regPercent)
    }

    fun getActiveMaxDiscountAmount(client: Client): Double? {
        val taskReward = findActiveTaskReward(client)
        val codeReward = findActivePromoUsage(client)
        val regPlan = findActiveRegistrationDiscountPlan(client)

        val taskPercent = taskReward?.promoTask?.discountPercent ?: 0.0
        val codePercent = codeReward?.promoCode?.discountPercent ?: 0.0
        val regPercent = regPlan?.discountPercent ?: 0.0

        val maxPercent = maxOf(taskPercent, codePercent, regPercent)
        if (maxPercent == 0.0) return null

        return when (maxPercent) {
            regPercent -> regPlan?.maxDiscountAmount
            taskPercent -> taskReward?.promoTask?.maxDiscountAmount
            else -> codeReward?.promoCode?.maxDiscountAmount
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
                    if (!it.isUsed) {
                        it.isUsed = true
                        it.usedAt = LocalDateTime.now()
                        promoUsageRepository.save(it)

                        val promo = it.promoCode
                        promo.usedCount += 1
                        promoCodeRepository.save(promo)
                    }
                }
            }
        }
    }

    fun getClientPromos(client: Client): List<ClientPromoProgress> {
        val now = LocalDateTime.now()
        val allTasks = promoTaskRepository.findAllByIsActiveTrue()
            .filter { it.expiresAt == null || it.expiresAt!!.isAfter(now) }

        val taskProgresses = allTasks.mapNotNull { task ->
            val progressOpt = progressRepository.findByClientIdAndPromoTaskId(client.id, task.id)

            val progress = if (progressOpt.isPresent) {
                progressOpt.get()
            } else {
                if (task.maxAllocations != null) {
                    val alreadyAllocated = progressRepository.countByPromoTaskId(task.id)
                    val slotsLeft = task.maxAllocations!! - alreadyAllocated
                    if (slotsLeft <= 0) return@mapNotNull null

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
                LocalDateTime.now().isAfter(progress.rewardExpiresAt)
            ) {
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