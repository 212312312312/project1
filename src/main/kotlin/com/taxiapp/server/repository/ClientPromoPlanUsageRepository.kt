package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.ClientPromoPlanUsage
import com.taxiapp.server.model.user.Client
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ClientPromoPlanUsageRepository : JpaRepository<ClientPromoPlanUsage, Long> {
    
    // Возвращает true, если клиент уже совершил успешную поездку по этой акции
    fun existsByClientAndPromoPlanId(client: Client, promoPlanId: Long): Boolean
}