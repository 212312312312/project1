package com.taxiapp.server.model.order

import jakarta.persistence.*
import org.hibernate.Hibernate
import java.time.LocalDateTime

@Entity
@Table(name = "car_tariffs")
data class CarTariff(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var name: String,

    @Column(name = "body_type", nullable = true)
    var bodyType: String? = null,

    @Column(nullable = false)
    var basePrice: Double,

    @Column(name = "evos_tariff_name", nullable = true)
    var evosTariffName: String? = null,

    @Column(nullable = false)
    var isBeta: Boolean = false,

    @Column(nullable = false)
    var isUnavailable: Boolean = false,

    @Column(nullable = false, columnDefinition = "double precision default 0.0")
    var extraWaypointPrice: Double = 0.0,

    @Column(nullable = false)
    var pricePerKm: Double,

    // --- НОВОЕ ПОЛЕ: Цена за городом ---
    @Column(nullable = false)
    var pricePerKmOutCity: Double = 0.0,
    // -----------------------------------

    @Column(nullable = false)
    var freeWaitingMinutes: Int = 3,

    @Column(nullable = false)
    var pricePerWaitingMinute: Double = 2.0,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "sort_order", nullable = false, columnDefinition = "integer default 0")
    var sortOrder: Int = 0,

    @Column(nullable = true)
    var imageUrl: String? = null,

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    /**
     * Безопасная реализация equals/hashCode для JPA/Hibernate 6:
     * исключает прямое обращение к backing-полю `this.name` на неинициализированных
     * HibernateProxy при добавлении тарифов в PersistentSet / HashSet.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as CarTariff
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = Hibernate.getClass(this).hashCode()

    override fun toString(): String = "${Hibernate.getClass(this).simpleName}(id=$id)"
}