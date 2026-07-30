package com.taxiapp.server.model.driver

import com.taxiapp.server.model.enums.PhotoControlStatus
import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "photo_controls")
class PhotoControl(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    val driver: Driver,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PhotoControlStatus = PhotoControlStatus.PENDING,

    @Column(nullable = false)
    val requestedAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    val deadlineAt: LocalDateTime = LocalDateTime.now().plusHours(1),

    var submittedAt: LocalDateTime? = null,
    var reviewedAt: LocalDateTime? = null,

    // Ссылки на 6 фото
    var frontUrl: String? = null,
    var backUrl: String? = null,
    var leftUrl: String? = null,
    var rightUrl: String? = null,
    var interiorFrontUrl: String? = null,
    var interiorBackUrl: String? = null,

    var rejectReason: String? = null,
    var reviewedByDispatcherId: Long? = null
)