package com.github.melancholic.fintrace.importer.domain

enum class CategoryType(val code: Int) {
    INCOME(2),
    EXPENSE(1);

    companion object {
        private val values = CategoryType.entries.associateBy { it.code }

        fun from(code: Int): CategoryType =
            values[code] ?: throw IllegalArgumentException("Unknown CategoryType code: $code")
    }
}