package com.taxiapp.server.model.surge

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalTime

@Entity
@Table(name = "time_surge_rules")
data class TimeSurgeRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var name: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "time_surge_days", joinColumns = [JoinColumn(name = "rule_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week")
    var daysOfWeek: MutableSet<DayOfWeek> = mutableSetOf(),

    @Column(nullable = false)
    var startTime: LocalTime,

    @Column(nullable = false)
    var endTime: LocalTime,

    @Column(nullable = false)
    var multiplier: Double = 1.0,

    @Column(nullable = false)
    var isActive: Boolean = true
)