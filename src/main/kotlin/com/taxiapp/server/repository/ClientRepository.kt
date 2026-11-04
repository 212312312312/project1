package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Client
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface ClientRepository : JpaRepository<Client, Long> {

    fun findByUserPhone(userPhone: String): Optional<Client>
    fun findByUserLogin(userLogin: String): Optional<Client>
    fun findByEmail(email: String): Optional<Client>

    // Поиск по части номера (для админки)
    fun findByUserPhoneContaining(userPhone: String): List<Client>

    @Query("""
        SELECT 
            COALESCE(c.utmSource, c.acquisitionSource), 
            c.utmMedium, 
            c.utmCampaign, 
            COUNT(c) 
        FROM Client c 
        WHERE c.id <> 999999
        GROUP BY COALESCE(c.utmSource, c.acquisitionSource), c.utmMedium, c.utmCampaign
    """)
    fun getTrafficSourceStats(): List<Array<Any>>

    @Query("""
        SELECT 
            COALESCE(c.utmSource, c.acquisitionSource), 
            c.utmMedium, 
            c.utmCampaign, 
            COUNT(c) 
        FROM Client c 
        WHERE c.id <> 999999
          AND COALESCE(c.createdAt, c.registrationDatetime) BETWEEN :startDate AND :endDate
        GROUP BY COALESCE(c.utmSource, c.acquisitionSource), c.utmMedium, c.utmCampaign
        ORDER BY COUNT(c) DESC
    """)
    fun getTrafficSourceStatsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query("SELECT COUNT(c) FROM Client c WHERE c.id <> 999999")
    fun countRealClients(): Long

    @Query("SELECT COUNT(c) FROM Client c WHERE c.id <> 999999 AND COALESCE(c.createdAt, c.registrationDatetime) BETWEEN :startDate AND :endDate")
    fun countRealClientsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): Long

    // HIGH-LOAD: Легковесная проекция для когортного анализа (вместо clientRepository.findAll() на 100k+ клиентов)
    @Query("SELECT c.registrationDatetime, c.totalCompletedOrders FROM Client c WHERE c.id <> 999999")
    fun findCohortRawData(): List<Array<Any>>

    // Подсчет подозрительных устройств (device_id, связанных более чем с 1 аккаунтом клиента)
    @Query(
        value = """
            WITH all_device_links AS (
                SELECT c.device_id AS dev_id, c.id AS client_id
                FROM clients c
                WHERE c.device_id IS NOT NULL AND TRIM(c.device_id) <> '' AND c.id <> 999999
                UNION
                SELECT o.client_device_id AS dev_id, o.client_id AS client_id
                FROM taxi_orders o
                WHERE o.client_device_id IS NOT NULL AND TRIM(o.client_device_id) <> '' AND o.client_id <> 999999
                UNION
                SELECT pu.device_id AS dev_id, pu.client_id AS client_id
                FROM promo_usages pu
                WHERE pu.device_id IS NOT NULL AND TRIM(pu.device_id) <> '' AND pu.client_id <> 999999
            ),
            multi_acc_devices AS (
                SELECT dev_id
                FROM all_device_links
                GROUP BY dev_id
                HAVING COUNT(DISTINCT client_id) > 1
            )
            SELECT COUNT(*) FROM multi_acc_devices
        """,
        nativeQuery = true
    )
    fun countSuspiciousMultiAccountDevices(): Long

    @Query(
        value = """
            SELECT c.id FROM clients c
            WHERE c.id NOT IN (SELECT cpp.client_id FROM client_promo_progress cpp WHERE cpp.promo_task_id = :taskId)
            ORDER BY c.trips_count DESC,
                     (CASE WHEN EXISTS (SELECT 1 FROM client_promo_progress cpp2 WHERE cpp2.client_id = c.id AND cpp2.is_reward_available = true) 
                             OR EXISTS (SELECT 1 FROM promo_usages pu WHERE pu.client_id = c.id AND pu.is_used = false) THEN 1 ELSE 0 END) ASC,
                     RANDOM()
            LIMIT :limit
        """,
        nativeQuery = true
    )
    fun findTopEligibleClientIdsForTask(
        @Param("taskId") taskId: Long,
        @Param("limit") limit: Int
    ): List<Long>
}