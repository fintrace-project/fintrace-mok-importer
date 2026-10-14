package com.github.melancholic.fintrace.importer.generator

import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate

enum class SyntheticDumpSize(val start: LocalDate, val end: LocalDate, val actionsPerDay: IntRange, val seed: Long) {
    S(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 12, 31), 1..3, 20240701L),
}

data class ChronicleRow(val uid: Long, val badge: Long, val type: Int, val params: String)

data class ExpectedAccount(
    val uid: Long,
    val name: String,
    val currency: String,
    val archived: Boolean,
    val balance: BigDecimal,
)

data class ExpectedImport(
    val size: String,
    val seed: Long,
    val rows: Int,
    val accounts: List<ExpectedAccount>,
    val categories: Int,
    val operations: Int,
    val transfers: Int,
    val diagnostics: Int,
)

class SyntheticDump(val size: SyntheticDumpSize, val rows: List<ChronicleRow>, val expected: ExpectedImport) {

    fun toSql(): String = buildString {
        append("-- Synthetic MOK dump ${size.name} (seed ${size.seed}). Generated: ./gradlew generateSyntheticDumps\n")
        append("CREATE TABLE Chronicle(uid INTEGER PRIMARY KEY AUTOINCREMENT, badge INTEGER, type INTEGER, params TEXT);\n")
        append("CREATE TABLE android_metadata (locale TEXT);\n")
        append("INSERT INTO android_metadata VALUES ('en_US');\n")
        rows.forEach { row ->
            append("INSERT INTO Chronicle VALUES (${row.uid}, ${row.badge}, ${row.type}, '${row.params.replace("'", "''")}');\n")
        }
    }

    fun toExpectedJson(): String = MAPPER.writeValueAsString(expected) + "\n"

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build()

        fun sqlFileName(size: SyntheticDumpSize) = "mok-synthetic-${size.name}-dump.sql"
        fun expectedFileName(size: SyntheticDumpSize) = "mok-synthetic-${size.name}-dump.expected.json"
        fun mokFileName(size: SyntheticDumpSize) = "mok-synthetic-${size.name}-dump.mok"

        // A .mok export is the app's SQLite database file under another extension.
        fun buildSqlite(sql: String, file: Path): Path {
            Files.deleteIfExists(file)
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    sql.lineSequence()
                        .filter { it.isNotBlank() && !it.startsWith("--") }
                        .forEach { statement.executeUpdate(it) }
                }
                connection.commit()
            }
            return file
        }
    }
}
