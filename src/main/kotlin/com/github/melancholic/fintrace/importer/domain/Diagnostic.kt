package com.github.melancholic.fintrace.importer.domain

data class Diagnostic(
    val severity: Severity,
    val code: Code,
    val count: Long,
    val detail: String? = null,
) {
    enum class Severity { WARNING, ERROR }

    enum class Code { UNSUPPORTED_RECORD, UNKNOWN_CURRENCY, UNRESOLVED_REFERENCE, SOURCE_ANOMALY }
}