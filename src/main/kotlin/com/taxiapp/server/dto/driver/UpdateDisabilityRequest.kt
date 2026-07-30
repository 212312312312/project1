package com.taxiapp.server.dto.driver

data class UpdateDisabilityRequest(
    val hasMovementIssue: Boolean,
    val hasHearingIssue: Boolean,
    val isDeaf: Boolean,
    val hasSpeechIssue: Boolean
)