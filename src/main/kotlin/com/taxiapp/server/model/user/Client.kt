package com.taxiapp.server.model.user

import com.taxiapp.server.model.order.TaxiOrder
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(name = "clients")
class Client : User() { // Унаследуем от User
    @OneToMany(mappedBy = "client")
    val orders: List<TaxiOrder> = emptyList()
}