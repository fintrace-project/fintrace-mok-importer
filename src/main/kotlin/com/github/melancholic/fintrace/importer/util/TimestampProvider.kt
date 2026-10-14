package com.github.melancholic.fintrace.importer.util

import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

interface TimestampProvider {
    fun now(): LocalDateTime
}

@Component
class TimestampProviderImpl : TimestampProvider {
    override fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)
}