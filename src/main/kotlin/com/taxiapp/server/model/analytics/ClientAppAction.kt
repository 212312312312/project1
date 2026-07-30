package com.taxiapp.server.model.analytics

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "client_app_actions")
class ClientAppAction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "client_id", nullable = false)
    val clientId: Long,

    @Column(name = "action_name", nullable = false)
    val actionName: String,

    @Column(name = "action_value")
    val actionValue: String?,

    @Column(name = "session_id", nullable = false)
    val sessionId: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)