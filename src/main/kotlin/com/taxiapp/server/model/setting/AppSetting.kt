package com.taxiapp.server.model.setting

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "app_settings")
data class AppSetting(
    @Id
    @Column(name = "setting_key", nullable = false, unique = true)
    val key: String, // Наприклад: "driver_map_icon", "client_car_icon"

    @Column(name = "setting_value", length = 1024)
    var value: String? // URL до картинки
)