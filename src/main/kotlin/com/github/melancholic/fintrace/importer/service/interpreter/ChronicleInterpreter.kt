package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.ChronicleSnapshot
import com.github.melancholic.fintrace.importer.domain.ParsedChronicle
import com.github.melancholic.fintrace.importer.service.UUIDGenerator
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ChronicleInterpreter internal constructor(
    private val applier: ChronicleBaseApplier,
    private val uuidGenerator: UUIDGenerator,
) {
    fun interpretChronicle(chronicle: ParsedChronicle, now: LocalDateTime, defaultCurrency: String): ChronicleSnapshot =
        ChronicleSnapshotBuilder(applier, uuidGenerator, now, defaultCurrency).run(chronicle)
}
