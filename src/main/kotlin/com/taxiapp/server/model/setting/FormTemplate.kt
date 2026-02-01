package com.taxiapp.server.model.setting

import jakarta.persistence.*

@Entity
@Table(name = "form_templates")
data class FormTemplate(
    @Id
    val keyName: String, // Например: "driver_registration" или "add_car"

    @Column(columnDefinition = "TEXT")
    var schemaJson: String // JSON структура полей: [{type: "text", name: "license_plate", required: true}, ...]
)