package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Car
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

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
    val photoSeatsBack: String?
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
        
        photoUrl = generateUrl(car.photoUrl),
        
        techPassportFront = generateUrl(car.techPassportFront),
        techPassportBack = generateUrl(car.techPassportBack),
        insurancePhoto = generateUrl(car.insurancePhoto), // Убедись, что в Car.kt поле называется insurancePhoto
        
        photoFront = generateUrl(car.photoFront),
        photoBack = generateUrl(car.photoBack),
        photoLeft = generateUrl(car.photoLeft),
        photoRight = generateUrl(car.photoRight),
        photoSeatsFront = generateUrl(car.photoSeatsFront),
        photoSeatsBack = generateUrl(car.photoSeatsBack)
    )

    companion object {
        private fun generateUrl(filename: String?): String? {
            if (filename.isNullOrBlank()) return null
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/images/")
                .path(filename)
                .toUriString()
        }
    }
}