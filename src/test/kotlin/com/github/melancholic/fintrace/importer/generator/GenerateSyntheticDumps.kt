package com.github.melancholic.fintrace.importer.generator

import java.nio.file.Files
import java.nio.file.Path

/** Args: the directory for the committed SQL and expected values, then optionally one for binary `.mok` dumps. */
fun main(args: Array<String>) {
    val directory = Files.createDirectories(Path.of(args[0]))
    val mokDirectory = args.getOrNull(1)?.let { Files.createDirectories(Path.of(it)) }
    SyntheticDumpSize.entries.forEach { size ->
        val dump = SyntheticDumpGenerator(size).generate()
        Files.writeString(directory.resolve(SyntheticDump.sqlFileName(size)), dump.toSql())
        Files.writeString(directory.resolve(SyntheticDump.expectedFileName(size)), dump.toExpectedJson())
        println("${size.name}: ${dump.rows.size} rows -> $directory")
        mokDirectory?.let {
            val mok = SyntheticDump.buildSqlite(dump.toSql(), it.resolve(SyntheticDump.mokFileName(size)))
            println("${size.name}: $mok")
        }
    }
}
