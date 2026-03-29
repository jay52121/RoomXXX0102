package com.example.roomxxx_vocie.kws

data class KwsHit(
    val keywordRaw: String,
    val timestampMs: Long,
    val score: Float?
)
