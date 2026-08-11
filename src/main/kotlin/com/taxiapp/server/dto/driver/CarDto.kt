package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Car

data class CarDto(
    val id: Long,
    val make: String,
    val model: String,
    val color: String,
    val plateNumber: String,
    val vin: String?,
    val year: Int,
    val carType: String?,
    val photoUrl: String?, // Главное фото

    val status: String?,
    val rejectionReason: String?,

    // Документы
    val techPassportFront: String?,
    val techPassportBack: String?,
    val insurancePhoto: String?,
    
    // Фото сторон
    val photoFront: String?,
    val photoBack: String?,
    val photoLeft: String?,
    val photoRight: String?,
    val photoSeatsFront: String?,
    val photoSeatsBack: String?,
    val photoTrunk: String?
) {
    constructor(car: Car) : this(
        id = car.id!!,
        make = car.make,
        model = car.model,
        color = car.color,
        plateNumber = car.plateNumber,
        vin = car.vin,
        year = car.year,
        carType = car.carType,
        status = car.status.name,
        rejectionReason = car.rejectionReason,
        
        // ВСЕ ФОТО ТЕПЕРЬ ЗАПРАШИВАЮТ СЖАТУЮ МИНИАТЮРУ (isThumbnail = true)
        photoUrl = generateUrl(if (!car.photoUrl.isNullOrBlank()) car.photoUrl else car.photoRight, isThumbnail = true),
        
        techPassportFront = generateUrl(car.techPassportFront, isThumbnail = true),
        techPassportBack = generateUrl(car.techPassportBack, isThumbnail = true),
        insurancePhoto = generateUrl(car.insurancePhoto, isThumbnail = true),
        
        photoFront = generateUrl(car.photoFront, isThumbnail = true),
        photoBack = generateUrl(car.photoBack, isThumbnail = true),
        photoLeft = generateUrl(car.photoLeft, isThumbnail = true),
        photoTrunk = generateUrl(car.photoTrunk, isThumbnail = true),
        photoRight = generateUrl(car.photoRight, isThumbnail = true),
        photoSeatsFront = generateUrl(car.photoSeatsFront, isThumbnail = true),
        photoSeatsBack = generateUrl(car.photoSeatsBack, isThumbnail = true)
    )

    companion object {
        fun generateUrl(filename: String?, isThumbnail: Boolean = false): String? {
            if (filename.isNullOrBlank()) return null
            if (filename.startsWith("http://") || filename.startsWith("https://")) return filename
            
            val clean = filename.trimStart('/')
            val rawName = if (clean.contains("/")) clean.substringAfterLast('/') else clean
            val baseName = if (rawName.startsWith("thumb_")) rawName.substringAfter("thumb_") else rawName
            
            val finalName = if (isThumbnail) "thumb_$baseName" else baseName
            return "/images/$finalName"
        }
    }
}