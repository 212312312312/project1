package com.taxiapp.server.repository

import org.springframework.data.jpa.repository.Query
import com.taxiapp.server.model.user.Client
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface ClientRepository : JpaRepository<Client, Long> {
    
    // ДОБАВЛЕНО: Поиск по точному номеру (нужен для контроллера)
    fun findByUserPhone(userPhone: String): Optional<Client>

    // Поиск по части номера (для админки)
    fun findByUserPhoneContaining(userPhone: String): List<Client>

    @Query("""
        SELECT 
            COALESCE(c.utmSource, c.acquisitionSource), 
            c.utmMedium, 
            c.utmCampaign, 
            COUNT(c) 
        FROM Client c 
        GROUP BY COALESCE(c.utmSource, c.acquisitionSource), c.utmMedium, c.utmCampaign
    """)
    fun getTrafficSourceStats(): List<Array<Any>>

    
    @Query(value = """
        SELECT c.id FROM clients c
        WHERE c.id NOT IN (SELECT cpp.client_id FROM client_promo_progress cpp WHERE cpp.promo_task_id = :taskId)
        ORDER BY c.trips_count DESC,
                 (CASE WHEN EXISTS (SELECT 1 FROM client_promo_progress cpp2 WHERE cpp2.client_id = c.id AND cpp2.is_reward_available = true) 
                         OR EXISTS (SELECT 1 FROM promo_usages pu WHERE pu.client_id = c.id AND pu.is_used = false) THEN 1 ELSE 0 END) ASC,
                 RANDOM()
        LIMIT :limit
    """, nativeQuery = true)
    fun findTopEligibleClientIdsForTask(taskId: Long, limit: Int): List<Long>
    
}