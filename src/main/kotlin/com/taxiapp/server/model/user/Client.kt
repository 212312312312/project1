package com.taxiapp.server.model.user

import com.taxiapp.server.model.order.TaxiOrder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

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
}