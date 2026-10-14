package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.domain.ChronicleDump
import com.github.melancholic.fintrace.importer.domain.RawMokOperation
import com.github.melancholic.fintrace.importer.domain.RenumberingWarning
import com.github.melancholic.fintrace.importer.exception.InvalidMokDumpException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException


interface MokOperationsLoader {
    fun load(dump: Path): ChronicleDump
}


@Service
class MokOperationsLoaderImpl : MokOperationsLoader {

    override fun load(dump: Path): ChronicleDump {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
        }

        try {
            DriverManager.getConnection("jdbc:sqlite:$dump", config.toProperties())
                .use { connection ->
                    val renumbering = checkRenumbering(connection)
                    if (renumbering != null) {
                        log.warn { "Dump was rebuilt: sqlite_sequence.seq = ${renumbering.sequence}, max(uid) = ${renumbering.maxUid}" }
                    }
                    return ChronicleDump(readOperations(connection), renumbering)
                }
        } catch (e: SQLException) {
            throw InvalidMokDumpException("Not a readable .mok dump: ${e.message}", e)
        }
    }

    private fun readOperations(connection: Connection): List<RawMokOperation> {
        val result = ArrayList<RawMokOperation>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT uid, type, params FROM $MAIN_TABLE_NAME ORDER BY uid").use { rs ->
                while (rs.next()) {
                    val uid = rs.getLong("uid")
                    val type = rs.getInt("type").takeUnless { rs.wasNull() }
                    val params = rs.getString("params")
                    result.add(RawMokOperation(uid, type, params))
                }
            }
        }
        return result
    }

    private fun checkRenumbering(connection: Connection): RenumberingWarning? {
        val maxUid = connection.queryLong("SELECT max(uid) FROM $MAIN_TABLE_NAME") ?: return null
        val hasSequenceTable = connection.queryLong(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'"
        ) == 1L
        val sequence = if (hasSequenceTable) {
            connection.queryLong("SELECT seq FROM sqlite_sequence WHERE name = '$MAIN_TABLE_NAME'")
        } else {
            null
        }
        return if (sequence != maxUid) {
            RenumberingWarning(sequence, maxUid)
        } else {
            null
        }
    }

    private fun Connection.queryLong(sql: String): Long? =
        createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (!rs.next()) return null
                rs.getLong(1).takeUnless { rs.wasNull() }
            }
        }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val MAIN_TABLE_NAME = "Chronicle"
    }
}