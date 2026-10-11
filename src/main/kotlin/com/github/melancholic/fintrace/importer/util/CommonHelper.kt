package com.github.melancholic.fintrace.importer.util

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong


private val FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
fun parseDateTime(stringDate: String): LocalDateTime {
    return LocalDateTime.parse(stringDate, FORMATTER)
}

fun JsonNode.asMoney(): BigDecimal =
    BigDecimal.valueOf((doubleValue() * 100).roundToLong() / 100.0).setScale(2, RoundingMode.UNNECESSARY)

fun JsonNode.asFlag(): Boolean = if (isBoolean) booleanValue() else asInt() != 0