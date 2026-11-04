package com.taxiapp.server.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taxiapp.server.dto.analytics.*
import com.taxiapp.server.model.analytics.ClientAppAction
import com.taxiapp.server.model.analytics.ClientAppEvent
import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.setting.AppSetting
import com.taxiapp.server.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToLong

@Service
class AnalyticsService(
    private val taxiOrderRepository: TaxiOrderRepository,
    private val clientRepository: ClientRepository,
    private val clientAppEventRepository: ClientAppEventRepository,
    private val clientAppActionRepository: ClientAppActionRepository,
    private val appSettingRepository: AppSettingRepository,
    private val walletTransactionRepository: WalletTransactionRepository
) {
    private val logger = LoggerFactory.getLogger(AnalyticsService::class.java)
    private val gson = Gson()

    private data class CacheEntry<T>(
        val data: T,
        val expiresAtMs: Long
    )

    private val generalCache = ConcurrentHashMap<String, CacheEntry<GeneralAnalyticsResponse>>()
    private val deepCache = ConcurrentHashMap<String, CacheEntry<DeepAnalyticsResponse>>()

    companion object {
        private const val CACHE_TTL_MS = 30_000L
        private const val KEY_MANUAL_FINANCE = "analytics_manual_finance_json"
        private val DEFAULT_MIN_DATE: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0)
        private val DISPLAY_DT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    fun invalidateCache() {
        generalCache.clear()
        deepCache.clear()
    }

    private fun resolvePeriodBounds(
        period: String?,
        startDateStr: String?,
        endDateStr: String?
    ): Triple<String, LocalDateTime, LocalDateTime> {
        val now = LocalDateTime.now()
        val normalizedPeriod = (period ?: "ALL").uppercase().trim()

        if (!startDateStr.isNullOrBlank() || !endDateStr.isNullOrBlank()) {
            val parsedStart = parseDateParam(startDateStr, isStartOfDay = true) ?: DEFAULT_MIN_DATE
            val parsedEnd = parseDateParam(endDateStr, isStartOfDay = false) ?: now.plusDays(1)
            return Triple("CUSTOM", parsedStart, parsedEnd)
        }

        return when (normalizedPeriod) {
            "TODAY", "1D" -> Triple("TODAY", LocalDate.now().atStartOfDay(), now.plusMinutes(5))
            "YESTERDAY" -> {
                val yesterday = LocalDate.now().minusDays(1)
                Triple("YESTERDAY", yesterday.atStartOfDay(), yesterday.atTime(23, 59, 59))
            }
            "7D", "WEEK" -> Triple("7D", LocalDate.now().minusDays(6).atStartOfDay(), now.plusMinutes(5))
            "30D", "MONTH" -> Triple("30D", LocalDate.now().minusDays(29).atStartOfDay(), now.plusMinutes(5))
            "90D", "QUARTER" -> Triple("90D", LocalDate.now().minusDays(89).atStartOfDay(), now.plusMinutes(5))
            else -> Triple("ALL", DEFAULT_MIN_DATE, now.plusDays(1))
        }
    }

    private fun parseDateParam(raw: String?, isStartOfDay: Boolean): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim()
        return try {
            if (clean.length == 10) {
                val ld = LocalDate.parse(clean)
                if (isStartOfDay) ld.atStartOfDay() else ld.atTime(23, 59, 59)
            } else {
                LocalDateTime.parse(clean)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSqlDateTime(raw: Any?): LocalDateTime? {
        return when (raw) {
            is LocalDateTime -> raw
            is Timestamp -> raw.toLocalDateTime()
            is Instant -> LocalDateTime.ofInstant(raw, ZoneId.systemDefault())
            is OffsetDateTime -> raw.toLocalDateTime()
            is String -> try {
                LocalDateTime.parse(raw.replace(" ", "T").substringBefore("."))
            } catch (e: Exception) {
                null
            }
            else -> null
        }
    }

    private fun parseSqlLocalDate(raw: Any?): LocalDate? {
        return when (raw) {
            is LocalDate -> raw
            is java.sql.Date -> raw.toLocalDate()
            is LocalDateTime -> raw.toLocalDate()
            is Timestamp -> raw.toLocalDateTime().toLocalDate()
            is String -> try {
                LocalDate.parse(raw.trim().take(10))
            } catch (e: Exception) {
                null
            }
            else -> null
        }
    }

    private fun buildDailyRevenueTimeline(
        resolvedPeriod: String,
        startDt: LocalDateTime,
        endDt: LocalDateTime,
        commissionPercentSetting: Double,
        manualEntries: List<ManualFinanceEntryDto>
    ): List<DailyRevenuePointDto> {
        val rawRows = runCatching {
            taxiOrderRepository.getDailyRevenueTimelineByPeriod(startDt, endDt)
        }.onFailure { logger.warn("Daily revenue timeline query warning: ${it.message}") }.getOrDefault(emptyList())

        data class DayAgg(
            var ordersCount: Long = 0L,
            var gmv: Double = 0.0,
            var commission: Double = 0.0,
            var discounts: Double = 0.0,
            var manualNet: Double = 0.0
        )

        val dayMap = mutableMapOf<LocalDate, DayAgg>()
        val rate = (if (commissionPercentSetting > 0.0) commissionPercentSetting else 10.0) / 100.0

        for (row in rawRows) {
            val d = parseSqlLocalDate(row.getOrNull(0)) ?: continue
            val ordersCnt = (row.getOrNull(1) as? Number)?.toLong() ?: 0L
            if (ordersCnt <= 0L) continue

            val rawGmv = (row.getOrNull(2) as? Number)?.toDouble() ?: 0.0
            val rawComm = (row.getOrNull(3) as? Number)?.toDouble() ?: 0.0
            val disc = (row.getOrNull(4) as? Number)?.toDouble() ?: 0.0

            // Если в тестовых/акционных заказах price был 1 грн, восстанавливаем полный оборот до скидки (price + applied_discount)
            val effectiveGmv = maxOf(rawGmv, rawGmv + disc, ordersCnt * 100.0)
            val effectiveComm = maxOf(rawComm, effectiveGmv * rate, ordersCnt * 10.0)

            val agg = dayMap.getOrPut(d) { DayAgg() }
            agg.ordersCount += ordersCnt
            agg.gmv += effectiveGmv
            agg.commission += effectiveComm
            agg.discounts += disc
        }

        val today = LocalDate.now()
        val fmt = DateTimeFormatter.ofPattern("dd.MM")

        val targetDates: List<LocalDate> = when (resolvedPeriod) {
            "TODAY", "1D", "YESTERDAY", "7D", "WEEK" -> {
                val endDay = if (endDt.toLocalDate().isAfter(today)) today else endDt.toLocalDate()
                val startDay = endDay.minusDays(6)
                (0L..6L).map { startDay.plusDays(it) }
            }
            "30D", "MONTH" -> {
                val endDay = if (endDt.toLocalDate().isAfter(today)) today else endDt.toLocalDate()
                val startDay = endDay.minusDays(13)
                (0L..13L).map { startDay.plusDays(it) }
            }
            else -> {
                if (dayMap.isEmpty()) {
                    val startDay = today.minusDays(9)
                    (0L..9L).map { startDay.plusDays(it) }
                } else {
                    val sortedActive = dayMap.keys.sorted()
                    val firstActive = sortedActive.first()
                    val lastActive = sortedActive.last()
                    val spanDays = java.time.temporal.ChronoUnit.DAYS.between(firstActive, lastActive)

                    if (spanDays in 0..18) {
                        val startD = firstActive.minusDays(1)
                        val endD = if (lastActive.plusDays(1).isAfter(today)) today else lastActive.plusDays(1)
                        val totalD = java.time.temporal.ChronoUnit.DAYS.between(startD, endD).coerceIn(1, 20)
                        (0L..totalD).map { startD.plusDays(it) }
                    } else {
                        // Берем реальные дни с поездками и добавляем по 1 пустому дню вокруг разрывов, чтобы видеть и пики, и просадки!
                        val expanded = sortedSetOf<LocalDate>()
                        for (d in sortedActive) {
                            expanded.add(d)
                            val nextD = d.plusDays(1)
                            if (!dayMap.containsKey(nextD) && !nextD.isAfter(today)) {
                                expanded.add(nextD)
                            }
                        }
                        expanded.add(today)
                        expanded.toList().takeLast(14)
                    }
                }
            }
        }

        return targetDates.map { cursor ->
            val agg = dayMap[cursor]
            val comm = agg?.commission ?: 0.0
            val disc = agg?.discounts ?: 0.0
            val man = agg?.manualNet ?: 0.0
            // На графике по дням показываем заработанную за этот день комиссию с поездок (comm),
            // чтобы разовые ручные списания (-1500 грн) не сплющивали весь график поездок в прямую линию
            DailyRevenuePointDto(
                date = cursor.toString(),
                label = cursor.format(fmt),
                ordersCount = agg?.ordersCount ?: 0L,
                gmv = round1(agg?.gmv ?: 0.0),
                commission = round1(comm),
                discounts = round1(disc),
                manualNet = round1(man),
                netRevenue = round1(comm)
            )
        }
    }

    private fun round1(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    // --- РУЧНЫЕ ФИНАНСОВЫЕ ЗАПИСИ (ДОХОДЫ И ВЫПЛАТЫ ВРУЧНУЮ) ---
    private fun loadManualFinanceEntries(): MutableList<ManualFinanceEntryDto> {
        return try {
            val rawJson = appSettingRepository.findById(KEY_MANUAL_FINANCE).orElse(null)?.value
            if (rawJson.isNullOrBlank()) {
                mutableListOf()
            } else {
                val listType = object : TypeToken<MutableList<ManualFinanceEntryDto>>() {}.type
                gson.fromJson<MutableList<ManualFinanceEntryDto>>(rawJson, listType) ?: mutableListOf()
            }
        } catch (e: Exception) {
            logger.warn("Не вдалося прочитати ручні фінансові записи: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveManualFinanceEntries(entries: List<ManualFinanceEntryDto>) {
        val json = gson.toJson(entries)
        val setting = appSettingRepository.findById(KEY_MANUAL_FINANCE).orElse(AppSetting(KEY_MANUAL_FINANCE, json))
        setting.value = json
        appSettingRepository.save(setting)
        invalidateCache()
    }

    @Transactional
    fun addManualFinanceEntry(request: CreateManualFinanceRequest): UnitEconomicsDto {
        val cleanType = if (request.type.uppercase().trim() == "EXPENSE") "EXPENSE" else "INCOME"
        val cleanAmount = kotlin.math.abs(request.amount)
        val cleanComment = request.comment?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (cleanType == "INCOME") "Ручне внесення доходу" else "Ручна виплата / витрата"

        if (cleanAmount > 0.0) {
            val entries = loadManualFinanceEntries()
            entries.add(
                0,
                ManualFinanceEntryDto(
                    id = UUID.randomUUID().toString().substring(0, 8),
                    type = cleanType,
                    amount = round1(cleanAmount),
                    comment = cleanComment,
                    createdAt = LocalDateTime.now().format(DISPLAY_DT_FMT)
                )
            )
            // Храним до 100 последних ручных записей
            val trimmed = entries.take(100)
            saveManualFinanceEntries(trimmed)
        }

        val (_, startDt, endDt) = resolvePeriodBounds("ALL", null, null)
        val summaryRow = runCatching { taxiOrderRepository.getPeriodSummaryMetrics(startDt, endDt).firstOrNull() }.getOrNull()
        return buildUnitEconomics("ALL", startDt, endDt, summaryRow)
    }

    @Transactional
    fun deleteManualFinanceEntry(id: String): UnitEconomicsDto {
        val entries = loadManualFinanceEntries()
        val updated = entries.filterNot { it.id == id }
        saveManualFinanceEntries(updated)

        val (_, startDt, endDt) = resolvePeriodBounds("ALL", null, null)
        val summaryRow = runCatching { taxiOrderRepository.getPeriodSummaryMetrics(startDt, endDt).firstOrNull() }.getOrNull()
        return buildUnitEconomics("ALL", startDt, endDt, summaryRow)
    }

    private fun buildUnitEconomics(
        resolvedPeriod: String,
        startDt: LocalDateTime,
        endDt: LocalDateTime,
        summaryRow: Array<Any>?
    ): UnitEconomicsDto {
        val completedOrders = (summaryRow?.getOrNull(1) as? Number)?.toLong() ?: 0L
        val gmvSum = (summaryRow?.getOrNull(4) as? Number)?.toDouble() ?: 0.0
        val avgGrossCheck = (summaryRow?.getOrNull(5) as? Number)?.toDouble() ?: 0.0
        val ordersCommissionSum = (summaryRow?.getOrNull(6) as? Number)?.toDouble() ?: 0.0
        val totalDiscountsSum = (summaryRow?.getOrNull(7) as? Number)?.toDouble() ?: 0.0
        val totalWaitingSum = (summaryRow?.getOrNull(8) as? Number)?.toDouble() ?: 0.0
        val discountedOrdersCount = (summaryRow?.getOrNull(9) as? Number)?.toLong() ?: 0L

        val commissionPercentSetting = appSettingRepository.findById("driver_commission_percent")
            .orElse(null)?.value?.toDoubleOrNull() ?: 10.0

        // 1. Берем реальную сумму комиссий из wallet_transactions (как на вкладке "Фінанси компанії")
        val walletCommissionSum = runCatching {
            if (resolvedPeriod == "ALL") {
                walletTransactionRepository.sumTotalByOperationType(TransactionType.COMMISSION) ?: 0.0
            } else {
                walletTransactionRepository.sumTotalByOperationTypeAndPeriod(TransactionType.COMMISSION, startDt, endDt) ?: 0.0
            }
        }.getOrDefault(0.0)

        // 2. Расчетная комиссия от оборота GMV (на случай если старые заказы проводились без записи в wallet_transactions)
        val estimatedCommissionFromGmv = gmvSum * (commissionPercentSetting / 100.0)

        // Итоговая полученная комиссия: берем максимум из реальных списаний кошелька, суммы в заказах и расчетной ставки
        val effectiveCommissionSum = maxOf(walletCommissionSum, ordersCommissionSum, estimatedCommissionFromGmv)

        // 3. Ручные доходы (+) и ручные выплаты/расходы (-)
        val manualEntries = loadManualFinanceEntries()
        val manualIncomeSum = manualEntries.filter { it.type == "INCOME" }.sumOf { it.amount }
        val manualExpenseSum = manualEntries.filter { it.type == "EXPENSE" }.sumOf { it.amount }

        // 4. Чистый доход компании = Полученная комиссия + Ручные доходы - Ручные выплаты - Расходы на промо/скидки
        val netCompanyRevenue = effectiveCommissionSum + manualIncomeSum - manualExpenseSum - totalDiscountsSum

        val avgClientPayCheck = if (completedOrders > 0) {
            (gmvSum - totalDiscountsSum).coerceAtLeast(0.0) / completedOrders.toDouble()
        } else 0.0

        val avgNetCommissionPerOrder = if (completedOrders > 0) {
            netCompanyRevenue / completedOrders.toDouble()
        } else 0.0

        val discountedShare = if (completedOrders > 0) {
            (discountedOrdersCount.toDouble() / completedOrders.toDouble()) * 100.0
        } else 0.0

        val revenueTimeline = buildDailyRevenueTimeline(
            resolvedPeriod = resolvedPeriod,
            startDt = startDt,
            endDt = endDt,
            commissionPercentSetting = commissionPercentSetting,
            manualEntries = manualEntries
        )

        return UnitEconomicsDto(
            grossMerchandiseValue = round1(gmvSum),
            totalCommissionRevenue = round1(effectiveCommissionSum),
            totalPromoSubsidies = round1(totalDiscountsSum),
            manualIncome = round1(manualIncomeSum),
            manualExpense = round1(manualExpenseSum),
            netCompanyRevenue = round1(netCompanyRevenue),
            totalWaitingRevenue = round1(totalWaitingSum),
            avgGrossCheck = round1(avgGrossCheck),
            avgClientPayCheck = round1(avgClientPayCheck),
            avgNetCommissionPerOrder = round1(avgNetCommissionPerOrder),
            discountedOrdersCount = discountedOrdersCount,
            discountedOrdersSharePercent = round1(discountedShare),
            manualEntries = manualEntries,
            revenueTimeline = revenueTimeline
        )
    }

    @Transactional(readOnly = true)
    fun getGeneralAnalytics(
        period: String = "ALL",
        startDateStr: String? = null,
        endDateStr: String? = null
    ): GeneralAnalyticsResponse {
        val (resolvedPeriod, startDt, endDt) = resolvePeriodBounds(period, startDateStr, endDateStr)
        val cacheKey = "$resolvedPeriod|${startDt.toLocalDate()}|${endDt.toLocalDate()}"
        val nowMs = System.currentTimeMillis()

        generalCache[cacheKey]?.let {
            if (nowMs < it.expiresAtMs) return it.data
        }

        val commissionPercentSetting = appSettingRepository.findById("driver_commission_percent")
            .orElse(null)?.value?.toDoubleOrNull() ?: 10.0

        val summaryRow = runCatching {
            taxiOrderRepository.getPeriodSummaryMetrics(startDt, endDt).firstOrNull()
        }.getOrNull()

        val totalOrders = (summaryRow?.getOrNull(0) as? Number)?.toLong() ?: 0L
        val completedOrders = (summaryRow?.getOrNull(1) as? Number)?.toLong() ?: 0L
        val cancelledOrders = (summaryRow?.getOrNull(2) as? Number)?.toLong() ?: 0L
        val uniquePayingClients = (summaryRow?.getOrNull(3) as? Number)?.toLong() ?: 0L
        val gmvSum = (summaryRow?.getOrNull(4) as? Number)?.toDouble() ?: 0.0
        val avgGrossCheck = (summaryRow?.getOrNull(5) as? Number)?.toDouble() ?: 0.0

        val totalClientsCount = runCatching {
            if (resolvedPeriod == "ALL") {
                clientRepository.countRealClients()
            } else {
                clientRepository.countRealClientsByPeriod(startDt, endDt).coerceAtLeast(uniquePayingClients)
            }
        }.getOrDefault(uniquePayingClients)

        val conversionRate = if (totalClientsCount > 0) {
            round1((uniquePayingClients.toDouble() / totalClientsCount.toDouble()) * 100.0)
        } else 0.0

        val fulfillmentRate = if (totalOrders > 0) {
            round1((completedOrders.toDouble() / totalOrders.toDouble()) * 100.0)
        } else 0.0

        val averageLtv = if (uniquePayingClients > 0) {
            round1(gmvSum / uniquePayingClients.toDouble())
        } else 0.0

        val tariffStats = runCatching {
            taxiOrderRepository.getTariffAnalyticsByPeriod(startDt, endDt).map { row ->
                val tName = row.getOrNull(0) as? String ?: "Невідомий тариф"
                val compCount = (row.getOrNull(1) as? Number)?.toLong() ?: 0L
                val cancCount = (row.getOrNull(2) as? Number)?.toLong() ?: 0L
                val rev = (row.getOrNull(3) as? Number)?.toDouble() ?: 0.0
                val avg = (row.getOrNull(4) as? Number)?.toDouble() ?: 0.0
                val rawComm = (row.getOrNull(5) as? Number)?.toDouble() ?: 0.0
                val disc = (row.getOrNull(6) as? Number)?.toDouble() ?: 0.0
                // Если в старых заказах commission_amount был 0, считаем комиссию по ставке сервиса
                val effectiveComm = if (rawComm > 0.0) rawComm else rev * (commissionPercentSetting / 100.0)
                TariffStatDto(
                    tariffName = tName,
                    orderCount = compCount,
                    totalRevenue = round1(rev),
                    cancelledCount = cancCount,
                    avgCheck = round1(avg),
                    totalCommission = round1(effectiveComm),
                    totalDiscounts = round1(disc),
                    netRevenue = round1(effectiveComm - disc)
                )
            }
        }.getOrDefault(emptyList())

        val screenStats = runCatching {
            clientAppEventRepository.getScreenStatsByPeriod(startDt, endDt).map { row ->
                val avgSeconds = (row.getOrNull(2) as? Number)?.toDouble() ?: 0.0
                ScreenStatDto(
                    screenName = row.getOrNull(0) as? String ?: "UnknownScreen",
                    visitCount = (row.getOrNull(1) as? Number)?.toLong() ?: 0L,
                    averageDurationSeconds = round1(avgSeconds)
                )
            }
        }.getOrDefault(emptyList())

        val trafficStats = runCatching {
            val trafficRaw = if (resolvedPeriod == "ALL") {
                clientRepository.getTrafficSourceStats()
            } else {
                clientRepository.getTrafficSourceStatsByPeriod(startDt, endDt)
            }
            trafficRaw.map { row ->
                val rawSource = row.getOrNull(0) as? String
                TrafficSourceStatDto(
                    source = rawSource ?: "Органічний трафік (Пряме встановлення)",
                    medium = row.getOrNull(1) as? String ?: (if (rawSource == null) "organic" else "not_set"),
                    campaign = row.getOrNull(2) as? String ?: (if (rawSource == null) "Прямий візит" else "not_set"),
                    userCount = (row.getOrNull(3) as? Number)?.toLong() ?: 0L
                )
            }
        }.getOrDefault(emptyList())

        val actionStats = runCatching {
            clientAppActionRepository.getActionStatsByPeriod(startDt, endDt).map { row ->
                ActionStatDto(
                    actionName = row.getOrNull(0) as? String ?: "",
                    actionValue = row.getOrNull(1) as? String,
                    count = (row.getOrNull(2) as? Number)?.toLong() ?: 0L,
                    uniqueSessions = (row.getOrNull(3) as? Number)?.toLong() ?: 0L,
                    uniqueUsers = (row.getOrNull(4) as? Number)?.toLong() ?: 0L
                )
            }
        }.getOrDefault(emptyList())

        val clientCancellationStats = runCatching {
            val clientCancellationsRaw = taxiOrderRepository.getCancellationStatsByPeriod(startDt, endDt)
            val totalClientCancellations = clientCancellationsRaw.sumOf { it.count }.toDouble()
            clientCancellationsRaw.map { row ->
                val count = row.count
                val percentage = if (totalClientCancellations > 0) {
                    round1((count.toDouble() / totalClientCancellations) * 100.0)
                } else 0.0
                ClientCancellationStatDto(
                    reason = row.reason,
                    count = count,
                    percentage = percentage
                )
            }
        }.getOrDefault(emptyList())

        val unitEconomics = buildUnitEconomics(resolvedPeriod, startDt, endDt, summaryRow)

        val slaRow = runCatching {
            taxiOrderRepository.getSlaAndBoostMetricsByPeriod(startDt, endDt).firstOrNull()
        }.getOrNull()
        val genSla = SlaMetricsDto(
            avgTimeToAcceptSeconds = round1((slaRow?.getOrNull(0) as? Number)?.toDouble() ?: 0.0),
            avgTimeToArriveSeconds = round1((slaRow?.getOrNull(1) as? Number)?.toDouble() ?: 0.0),
            avgTripDurationSeconds = round1((slaRow?.getOrNull(2) as? Number)?.toDouble() ?: 0.0),
            avgTripDistanceKm = round1((slaRow?.getOrNull(3) as? Number)?.toDouble() ?: 0.0),
            scheduledOrdersCount = (slaRow?.getOrNull(6) as? Number)?.toLong() ?: 0L,
            scheduledFulfillmentRate = 0.0
        )

        val response = GeneralAnalyticsResponse(
            averageOrderValue = round1(avgGrossCheck),
            totalLtvSum = round1(gmvSum),
            averageLtv = averageLtv,
            conversionRate = conversionRate,
            fulfillmentRate = fulfillmentRate,
            tariffStats = tariffStats,
            screenStats = screenStats,
            trafficStats = trafficStats,
            actionStats = actionStats,
            clientCancellationStats = clientCancellationStats,
            totalOrdersCount = totalOrders,
            completedOrdersCount = completedOrders,
            cancelledOrdersCount = cancelledOrders,
            totalClientsCount = totalClientsCount,
            uniquePayingClientsCount = uniquePayingClients,
            unitEconomics = unitEconomics,
            sla = genSla
        )

        generalCache[cacheKey] = CacheEntry(response, nowMs + CACHE_TTL_MS)
        return response
    }

    @Transactional(readOnly = true)
    fun getDeepAnalytics(
        period: String = "ALL",
        startDateStr: String? = null,
        endDateStr: String? = null
    ): DeepAnalyticsResponse {
        val (resolvedPeriod, startDt, endDt) = resolvePeriodBounds(period, startDateStr, endDateStr)
        val cacheKey = "$resolvedPeriod|${startDt.toLocalDate()}|${endDt.toLocalDate()}"
        val nowMs = System.currentTimeMillis()

        deepCache[cacheKey]?.let {
            if (nowMs < it.expiresAtMs) return it.data
        }

        val general = getGeneralAnalytics(period, startDateStr, endDateStr)
        val totalOrders = general.totalOrdersCount
        val completedOrders = general.completedOrdersCount
        val unitEconomics = general.unitEconomics

        // 1. SLA и Boost метрики (с защитой runCatching)
        val slaRow = runCatching {
            taxiOrderRepository.getSlaAndBoostMetricsByPeriod(startDt, endDt).firstOrNull()
        }.onFailure { logger.warn("SLA query warning: ${it.message}") }.getOrNull()

        val avgTimeToAccept = (slaRow?.getOrNull(0) as? Number)?.toDouble() ?: 0.0
        val avgTimeToArrive = (slaRow?.getOrNull(1) as? Number)?.toDouble() ?: 0.0
        val avgTripDuration = (slaRow?.getOrNull(2) as? Number)?.toDouble() ?: 0.0
        val avgDistanceKm = (slaRow?.getOrNull(3) as? Number)?.toDouble() ?: 0.0
        val boostOrdersCount = (slaRow?.getOrNull(4) as? Number)?.toLong() ?: 0L
        val boostCompletedCount = (slaRow?.getOrNull(5) as? Number)?.toLong() ?: 0L
        val scheduledOrdersCount = (slaRow?.getOrNull(6) as? Number)?.toLong() ?: 0L
        val scheduledCompletedCount = (slaRow?.getOrNull(7) as? Number)?.toLong() ?: 0L

        val slaMetrics = SlaMetricsDto(
            avgTimeToAcceptSeconds = round1(avgTimeToAccept),
            avgTimeToArriveSeconds = round1(avgTimeToArrive),
            avgTripDurationSeconds = round1(avgTripDuration),
            avgTripDistanceKm = round1(avgDistanceKm),
            scheduledOrdersCount = scheduledOrdersCount,
            scheduledFulfillmentRate = if (scheduledOrdersCount > 0) {
                round1((scheduledCompletedCount.toDouble() / scheduledOrdersCount.toDouble()) * 100.0)
            } else 0.0
        )

        // 2. Детализация отмен 360°
        val cancelRow = runCatching {
            taxiOrderRepository.getCancellationBreakdownByPeriod(startDt, endDt).firstOrNull()
        }.onFailure { logger.warn("Cancel breakdown warning: ${it.message}") }.getOrNull()

        val totalCancelled = (cancelRow?.getOrNull(0) as? Number)?.toLong() ?: general.cancelledOrdersCount
        val cancelledBeforeAccept = (cancelRow?.getOrNull(1) as? Number)?.toLong() ?: 0L
        val cancelledAfterAccept = (cancelRow?.getOrNull(2) as? Number)?.toLong() ?: 0L
        val cancelledByEvos = (cancelRow?.getOrNull(3) as? Number)?.toLong() ?: 0L
        val cancelledByDispatcher = (cancelRow?.getOrNull(4) as? Number)?.toLong() ?: 0L
        val quickCancels = (cancelRow?.getOrNull(5) as? Number)?.toLong() ?: 0L
        val timeoutCancels = (cancelRow?.getOrNull(6) as? Number)?.toLong() ?: 0L

        val cancellationBreakdown = CancellationBreakdownDto(
            totalCancelled = totalCancelled,
            cancelledBeforeAccept = cancelledBeforeAccept,
            cancelledAfterAccept = cancelledAfterAccept,
            cancelledByPartnerEvos = cancelledByEvos,
            cancelledByDispatcher = cancelledByDispatcher,
            quickCancelUnder60s = quickCancels,
            timeoutCancelOver180s = timeoutCancels
        )

        val kpis = OperationalKpiDto(
            fulfillmentRate = general.fulfillmentRate,
            avgTimeToAcceptSeconds = round1(avgTimeToAccept),
            avgTimeToArriveSeconds = round1(avgTimeToArrive),
            boostOrdersPercent = if (totalOrders > 0) round1((boostOrdersCount.toDouble() / totalOrders) * 100.0) else 0.0,
            boostFulfillmentPercent = if (boostOrdersCount > 0) round1((boostCompletedCount.toDouble() / boostOrdersCount) * 100.0) else 0.0,
            quickClientCancelPercent = if (totalOrders > 0) round1((quickCancels.toDouble() / totalOrders) * 100.0) else 0.0,
            timeoutCancelPercent = if (totalOrders > 0) round1((timeoutCancels.toDouble() / totalOrders) * 100.0) else 0.0
        )

        // 3. Свой парк vs Партнерская биржа EvoS
        val fleetRow = runCatching {
            taxiOrderRepository.getFleetSplitByPeriod(startDt, endDt).firstOrNull()
        }.onFailure { logger.warn("Fleet split warning: ${it.message}") }.getOrNull()

        val ownFleetCompleted = (fleetRow?.getOrNull(0) as? Number)?.toLong() ?: completedOrders
        val evosCompleted = (fleetRow?.getOrNull(1) as? Number)?.toLong() ?: 0L
        val chainCount = (fleetRow?.getOrNull(2) as? Number)?.toLong() ?: 0L
        val autoFilterCount = (fleetRow?.getOrNull(3) as? Number)?.toLong() ?: 0L
        val etherCount = (fleetRow?.getOrNull(4) as? Number)?.toLong() ?: ownFleetCompleted
        val totalSplitOrders = (ownFleetCompleted + evosCompleted).coerceAtLeast(1L)

        val fleetSplit = FleetSplitDto(
            ownFleetOrders = ownFleetCompleted,
            evosOrders = evosCompleted,
            ownFleetSharePercent = if (completedOrders > 0) round1((ownFleetCompleted.toDouble() / totalSplitOrders) * 100.0) else 0.0,
            evosSharePercent = if (completedOrders > 0) round1((evosCompleted.toDouble() / totalSplitOrders) * 100.0) else 0.0,
            chainOrdersCount = chainCount,
            autoFilterOrdersCount = autoFilterCount,
            etherOrdersCount = etherCount
        )

        // 4. Почасовой спрос (0..23)
        val hourlyRawMap = runCatching {
            taxiOrderRepository.getHourlyDemandByPeriod(startDt, endDt).associateBy {
                (it.getOrNull(0) as? Number)?.toInt() ?: 0
            }
        }.onFailure { logger.warn("Hourly demand warning: ${it.message}") }.getOrDefault(emptyMap())

        val hourlyDemand = (0..23).map { hr ->
            val row = hourlyRawMap[hr]
            val hTotal = (row?.getOrNull(1) as? Number)?.toLong() ?: 0L
            val hComp = (row?.getOrNull(2) as? Number)?.toLong() ?: 0L
            val hCanc = (row?.getOrNull(3) as? Number)?.toLong() ?: 0L
            val hBoost = (row?.getOrNull(4) as? Number)?.toLong() ?: 0L
            HourlyDemandDto(
                hour = hr,
                totalOrders = hTotal,
                completedOrders = hComp,
                cancelledOrders = hCanc,
                fulfillmentRate = if (hTotal > 0) round1((hComp.toDouble() / hTotal) * 100.0) else 0.0,
                boostOrders = hBoost
            )
        }

        // 5. География спроса по секторам / районам / точкам подачи
        val sectorDemand = runCatching {
            taxiOrderRepository.getSectorDemandByPeriod(startDt, endDt).map { row ->
                val sName = row.getOrNull(0) as? String ?: "Центральний район"
                val sTotal = (row.getOrNull(1) as? Number)?.toLong() ?: 0L
                val sComp = (row.getOrNull(2) as? Number)?.toLong() ?: 0L
                val sCanc = (row.getOrNull(3) as? Number)?.toLong() ?: 0L
                val sAvgPrice = (row.getOrNull(4) as? Number)?.toDouble() ?: 0.0
                SectorDemandDto(
                    sectorName = sName,
                    totalOrders = sTotal,
                    completedOrders = sComp,
                    cancelledOrders = sCanc,
                    fulfillmentRate = if (sTotal > 0) round1((sComp.toDouble() / sTotal) * 100.0) else 0.0,
                    avgPrice = round1(sAvgPrice)
                )
            }
        }.onFailure { logger.warn("Sector demand warning: ${it.message}") }.getOrDefault(emptyList())

        // 6. Когортный анализ по завершенным поездкам (с fallback на клиентов)
        val cohorts = runCatching {
            val weekRangeFmt = DateTimeFormatter.ofPattern("dd.MM")
            val weekRangeYearFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

            fun toWeekMondayAndLabel(dt: LocalDateTime): Pair<LocalDate, String> {
                val date = dt.toLocalDate()
                val monday = date.with(java.time.DayOfWeek.MONDAY)
                val sunday = monday.plusDays(6)
                val label = "${monday.format(weekRangeFmt)} – ${sunday.format(weekRangeYearFmt)}"
                return Pair(monday, label)
            }

            val completedOrderCohorts = taxiOrderRepository.findClientCompletedOrderCohorts()
            val rawTriples = if (completedOrderCohorts.isNotEmpty()) {
                completedOrderCohorts.mapNotNull { row ->
                    val firstRideDt = parseSqlDateTime(row.getOrNull(1)) ?: return@mapNotNull null
                    val completedCount = (row.getOrNull(2) as? Number)?.toInt() ?: 0
                    val (monday, label) = toWeekMondayAndLabel(firstRideDt)
                    Triple(monday, label, completedCount)
                }
            } else {
                clientRepository.findCohortRawData().mapNotNull { row ->
                    val dt = parseSqlDateTime(row.getOrNull(0)) ?: return@mapNotNull null
                    val completedCount = (row.getOrNull(1) as? Number)?.toInt() ?: 0
                    val (monday, label) = toWeekMondayAndLabel(dt)
                    Triple(monday, label, completedCount)
                }
            }

            rawTriples
                .groupBy({ Pair(it.first, it.second) }, { it.third })
                .entries
                .sortedByDescending { it.key.first }
                .map { (weekKey, completedCounts) ->
                    val size = completedCounts.size.toDouble()
                    val ride2Count = completedCounts.count { it >= 2 }
                    val ride3Count = completedCounts.count { it >= 3 }
                    val ride5Count = completedCounts.count { it >= 5 }

                    CohortRetentionDto(
                        cohortWeek = weekKey.second,
                        totalUsers = completedCounts.size.toLong(),
                        ride2RetentionPercent = if (size > 0) round1((ride2Count / size) * 100.0) else 0.0,
                        ride3RetentionPercent = if (size > 0) round1((ride3Count / size) * 100.0) else 0.0,
                        ride5PlusRetentionPercent = if (size > 0) round1((ride5Count / size) * 100.0) else 0.0
                    )
                }
        }.onFailure { logger.warn("Cohorts warning: ${it.message}") }.getOrDefault(emptyList())

        // 7. Payback Period
        val configuredCac = appSettingRepository.findById("marketing_target_cac")
            .orElse(null)?.value?.toDoubleOrNull() ?: 120.0
        val avgCommissionPerOrder = if (completedOrders > 0 && unitEconomics.totalCommissionRevenue > 0.0) {
            round1(unitEconomics.totalCommissionRevenue / completedOrders.toDouble())
        } else {
            15.0
        }
        val targetRidesForPayback = if (avgCommissionPerOrder > 0.0) {
            ceil(configuredCac / avgCommissionPerOrder).toInt().coerceIn(1, 50)
        } else 8

        val avgDaysToPayback = runCatching {
            taxiOrderRepository.calculateAvgDaysToTargetRide(targetRidesForPayback)
        }.getOrDefault(0.0)

        val payback = PaybackPeriodDto(
            estimatedCac = round1(configuredCac),
            avgCommissionPerOrder = avgCommissionPerOrder,
            targetRidesForPayback = targetRidesForPayback,
            avgDaysToPayback = round1(avgDaysToPayback)
        )

        // 8. Anti-Fraud
        val blockedPromoAttempts = runCatching {
            clientAppActionRepository.countByActionNameAndPeriod("promo_blocked_device", startDt, endDt)
        }.getOrDefault(0L)

        val suspiciousDevicesCount = runCatching {
            clientRepository.countSuspiciousMultiAccountDevices()
        }.getOrDefault(0L)

        val fraud = FraudMetricDto(
            blockedPromoAttempts = blockedPromoAttempts,
            suspiciousDevicesCount = suspiciousDevicesCount
        )

        val response = DeepAnalyticsResponse(
            general = general,
            kpis = kpis,
            cohorts = cohorts,
            payback = payback,
            fraud = fraud,
            unitEconomics = unitEconomics,
            fleetSplit = fleetSplit,
            sla = slaMetrics,
            cancellationBreakdown = cancellationBreakdown,
            hourlyDemand = hourlyDemand,
            sectorDemand = sectorDemand,
            period = resolvedPeriod,
            periodStart = startDt.toLocalDate().toString(),
            periodEnd = endDt.toLocalDate().toString()
        )

        deepCache[cacheKey] = CacheEntry(response, nowMs + CACHE_TTL_MS)
        return response
    }

    @Transactional
    fun saveClientEvents(username: String, request: ClientEventBatchRequest) {
        val client = if (username.isNotBlank()) {
            clientRepository.findByUserPhone(username).orElseGet {
                clientRepository.findByUserLogin(username).orElseGet {
                    clientRepository.findByEmail(username).orElse(null)
                }
            }
        } else null

        if (client == null) {
            return
        }

        var clientUpdated = false

        if (client.utmSource == null && !request.utmSource.isNullOrBlank()) {
            client.utmSource = request.utmSource
            client.utmMedium = request.utmMedium
            client.utmCampaign = request.utmCampaign
            clientUpdated = true
        }

        if (client.deviceId.isNullOrBlank() && request.customEvents.isNotEmpty()) {
            val appOpenEvent = request.customEvents.firstOrNull { it.eventName == "app_open" && !it.eventValue.isNullOrBlank() }
            val extractedDeviceId = appOpenEvent?.eventValue
                ?.split(",")
                ?.firstOrNull { it.trim().startsWith("device:") }
                ?.substringAfter("device:")
                ?.trim()

            if (!extractedDeviceId.isNullOrBlank() && extractedDeviceId != "unknown") {
                client.deviceId = extractedDeviceId
                clientUpdated = true
            }
        }

        if (clientUpdated) {
            clientRepository.save(client)
        }

        if (request.events.isNotEmpty()) {
            val entities = request.events.map { eventDto ->
                ClientAppEvent(
                    clientId = client.id!!,
                    screenName = eventDto.screenName,
                    sessionId = request.sessionId,
                    durationSeconds = eventDto.durationSeconds,
                    createdAt = LocalDateTime.now()
                )
            }
            clientAppEventRepository.saveAll(entities)
        }

        if (request.customEvents.isNotEmpty()) {
            val actionEntities = request.customEvents.map { actionDto ->
                ClientAppAction(
                    clientId = client.id!!,
                    actionName = actionDto.eventName,
                    actionValue = actionDto.eventValue,
                    sessionId = request.sessionId,
                    createdAt = LocalDateTime.now()
                )
            }
            clientAppActionRepository.saveAll(actionEntities)
        }
    }
}