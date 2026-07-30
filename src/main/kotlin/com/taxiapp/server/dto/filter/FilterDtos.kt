package com.taxiapp.server.dto.filter

data class UpdateFilterModeRequest(
    val isActive: Boolean,
    val isEther: Boolean, // <--- НОВЕ
    val isAuto: Boolean,
    val isCycle: Boolean
)

data class CreateFilterRequest(
    val name: String,
    
    val isEther: Boolean = false, // <--- НОВЕ
    val isAuto: Boolean = false,
    val isCycle: Boolean = false,

    val fromType: String,
    val fromDistance: Double?,
    val fromSectors: List<Long>,
    val toSectors: List<Long>,
    val tariffType: String,
    val minPrice: Double?,
    val minPricePerKm: Double?,
    val complexMinPrice: Double?,
    val complexKmInMin: Double?,      
    val complexPriceKmCity: Double?,
    val complexPriceKmSuburbs: Double?, 
    val paymentType: String
)

data class DriverFilterDto(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val isEther: Boolean, // <--- НОВЕ
    val isAuto: Boolean,   
    val isCycle: Boolean,  
    val description: String,
    val fromType: String,
    val fromDistance: Double?,
    val fromSectors: List<Long>,
    val toSectors: List<Long>,
    val tariffType: String,
    val minPrice: Double?,
    val minPricePerKm: Double?,
    val complexMinPrice: Double?,
    val complexKmInMin: Double?,      
    val complexPriceKmCity: Double?,
    val complexPriceKmSuburbs: Double?, 
    val paymentType: String
)