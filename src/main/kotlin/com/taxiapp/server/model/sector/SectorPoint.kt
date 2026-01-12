package com.taxiapp.server.model.sector

import com.fasterxml.jackson.annotation.JsonBackReference
import jakarta.persistence.*

@Entity
@Table(name = "sector_points")
class SectorPoint(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var lat: Double,

    @Column(nullable = false)
    var lng: Double,

    // Порядок точки в линии полигона (1, 2, 3...)
    @Column(name = "point_order", nullable = false)
    var pointOrder: Int,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sector_id")
    @JsonBackReference
    var sector: Sector? = null
)