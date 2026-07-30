package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.enums.PhotoControlStatus
import java.time.LocalDateTime

data class RequestPhotoControlDto(
    val driverId: Long
)

data class SubmitPhotoControlDto(
    val frontUrl: String,
    val backUrl: String,
    val leftUrl: String,
    val rightUrl: String,
    val interiorFrontUrl: String,
    val interiorBackUrl: String
)

data class ReviewPhotoControlDto(
    val approved: Boolean,
    val rejectReason: String? = null
)

data class PhotoControlStatusDto(
    val id: Long,
    val driverId: Long,
    val driverName: String,
    val status: PhotoControlStatus,
    val deadlineAt: LocalDateTime,
    val photoControlRestricted: Boolean,
    val frontUrl: String? = null,
    val backUrl: String? = null,
    val leftUrl: String? = null,
    val rightUrl: String? = null,
    val interiorFrontUrl: String? = null,
    val interiorBackUrl: String? = null,
    val rejectReason: String? = null
)