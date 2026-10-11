package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.domain.RawMokOperation

interface ImportRequestFactory {
    fun buildImportRequest(operations: List<RawMokOperation>)
}