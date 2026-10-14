package com.github.melancholic.fintrace.importer.generator

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val directory = Files.createDirectories(Path.of(args.single()))
    SyntheticDumpSize.entries.forEach { size ->
        val dump = SyntheticDumpGenerator(size).generate()
        Files.writeString(directory.resolve(SyntheticDump.sqlFileName(size)), dump.toSql())
        Files.writeString(directory.resolve(SyntheticDump.expectedFileName(size)), dump.toExpectedJson())
        println("${size.name}: ${dump.rows.size} rows -> $directory")
    }
}
