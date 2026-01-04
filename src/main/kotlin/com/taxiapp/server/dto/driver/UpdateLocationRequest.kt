package com.taxiapp.server.dto.driver

import com.fasterxml.jackson.annotation.JsonProperty

data class UpdateLocationRequest(
    // Связываем входящее поле "latitude" с нашей переменной lat
    @JsonProperty("latitude")
    val lat: Double,

    // Связываем входящее поле "longitude" с нашей переменной lng
    @JsonProperty("longitude")
    val lng: Double
)