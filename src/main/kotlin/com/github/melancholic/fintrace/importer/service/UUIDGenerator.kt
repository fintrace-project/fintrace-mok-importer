package com.github.melancholic.fintrace.importer.service

import com.fasterxml.uuid.Generators
import org.springframework.stereotype.Component
import java.util.UUID

interface UUIDGenerator {
    fun nextUUID(): UUID
}

@Component
class UUIDv7Generator : UUIDGenerator {
    override fun nextUUID(): UUID = generator.generate()

    companion object {
        private val generator = Generators.timeBasedEpochRandomGenerator()
    }
}