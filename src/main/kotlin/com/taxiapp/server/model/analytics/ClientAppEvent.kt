package com.taxiapp.server.model.analytics

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "client_app_events")
class ClientAppEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "client_id", nullable = false)
    val clientId: Long,

    @Column(name = "screen_name", nullable = false)
    val screenName: String,

    @Column(name = "session_id", nullable = false)
    val sessionId: String,

    @Column(name = "duration_seconds", nullable = false)
    val durationSeconds: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)