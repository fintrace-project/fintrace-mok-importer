package com.github.melancholic.fintrace.importer.domain

import java.math.BigDecimal

enum class CategoryType(val code: Int) {
    INCOME(2),
    EXPENSE(1);

    fun signed(amount: BigDecimal): BigDecimal = when (this) {
        INCOME -> amount
        EXPENSE -> amount.negate()
    }

    companion object {
        private val values = CategoryType.entries.associateBy { it.code }

        fun from(code: Int): CategoryType =
            values[code] ?: throw IllegalArgumentException("Unknown CategoryType code: $code")
    }
}