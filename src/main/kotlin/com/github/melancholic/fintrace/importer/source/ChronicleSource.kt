package com.github.melancholic.fintrace.importer.source

import java.nio.file.Path

/** Reads a dump that a transport has already put on disk; knows nothing about how it arrived. */
interface ChronicleSource {
    fun read(dump: Path): List<RawChronicleEvent>
}
