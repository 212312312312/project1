package com.taxiapp.server.model.user

import com.taxiapp.server.model.order.TaxiOrder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "clients")
class Client : User() {
    
    @OneToMany(mappedBy = "client")
    val orders: List<TaxiOrder> = emptyList()

    // --- РЕЙТИНГ ---
    @Column(nullable = false, columnDefinition = "double precision default 5.0")
    var rating: Double = 5.0

    @Column(nullable = false, columnDefinition = "integer default 0")
    var ratingCount: Int = 0
    // ------------------

    @Column(nullable = false, columnDefinition = "integer default 0")
    var tripsCount: Int = 0

   // --- ПРИВЯЗКА КАРТЫ ---
    @Column(name = "card_token", length = 255)
    var cardToken: String? = null

    @Column(name = "card_mask", length = 20)
    var cardMask: String? = null
    // ----------------------

    // --- МАРКЕТИНГОВАЯ АТРИБУЦИЯ (ИСПРАВЛЕНО: БЕЗ ЗАПЯТЫХ) ---
    @Column(name = "utm_source")
    var utmSource: String? = null

    @Column(name = "utm_medium")
    var utmMedium: String? = null

    @Column(name = "utm_campaign")
    var utmCampaign: String? = null

    @Column(name = "device_id", length = 128)
var deviceId: String? = null

@Column(name = "registration_datetime", nullable = false)
var registrationDatetime: LocalDateTime = LocalDateTime.now()

@Column(name = "acquisition_channel")
var acquisitionChannel: String? = null // Meta, Google, ЖК-чат и т.д.

@Column(name = "total_completed_orders", nullable = false, columnDefinition = "integer default 0")
var totalCompletedOrders: Int = 0
    // ----------------------

}