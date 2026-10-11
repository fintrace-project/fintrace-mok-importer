package com.github.melancholic.fintrace.importer

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.DriverManager

/** Builds a MoneyOK dump in a temp file from the recovered DDL (dump reference §0), so no real dump is ever committed. */
class ChronicleFixture(private val file: Path) {

    private val rows = ArrayList<Row>()
    private var sequence: Sequence = Sequence.Natural

    fun row(type: Int?, params: String?, uid: Long? = null) = apply { rows.add(Row(uid, type, params)) }

    fun sequence(seq: Long) = apply { sequence = Sequence.Fixed(seq) }

    fun withoutSequenceRow() = apply { sequence = Sequence.Missing }

    fun write(): Path {
        DriverManager.getConnection("jdbc:sqlite:$file", SQLiteConfig().toProperties()).use { connection ->
            connection.createStatement().use { it.executeUpdate(DDL) }
            connection.createStatement().use { it.executeUpdate("CREATE TABLE android_metadata (locale TEXT)") }
            connection.prepareStatement("INSERT INTO Chronicle (uid, badge, type, params) VALUES (?, ?, ?, ?)").use { insert ->
                rows.forEachIndexed { index, row ->
                    if (row.uid == null) insert.setNull(1, java.sql.Types.INTEGER) else insert.setLong(1, row.uid)
                    insert.setLong(2, 75_000_000L + index)
                    if (row.type == null) insert.setNull(3, java.sql.Types.INTEGER) else insert.setInt(3, row.type)
                    insert.setString(4, row.params)
                    insert.executeUpdate()
                }
            }
            when (val s = sequence) {
                Sequence.Natural -> Unit
                is Sequence.Fixed -> connection.createStatement().use {
                    it.executeUpdate("UPDATE sqlite_sequence SET seq = ${s.seq} WHERE name = 'Chronicle'")
                }
                Sequence.Missing -> connection.createStatement().use {
                    it.executeUpdate("DELETE FROM sqlite_sequence WHERE name = 'Chronicle'")
                }
            }
        }
        return file
    }

    private data class Row(val uid: Long?, val type: Int?, val params: String?)

    private sealed interface Sequence {
        data object Natural : Sequence
        data class Fixed(val seq: Long) : Sequence
        data object Missing : Sequence
    }

    private companion object {
        const val DDL = "CREATE TABLE Chronicle(uid INTEGER PRIMARY KEY AUTOINCREMENT, badge INTEGER, type INTEGER, params TEXT)"
    }
}
