package com.github.melancholic.fintrace.importer.domain

data class ParsedChronicle(
    val events: List<MokOperation>,
    val version: Int?,
    val diagnostics: List<Diagnostic>,
    val renumbering: RenumberingWarning?,
)