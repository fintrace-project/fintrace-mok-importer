package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.ChronicleFixture
import com.github.melancholic.fintrace.importer.domain.RawMokOperation
import com.github.melancholic.fintrace.importer.domain.RenumberingWarning
import com.github.melancholic.fintrace.importer.exception.InvalidMokDumpException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MokOperationsLoaderTest {

    @TempDir
    lateinit var dir: Path

    private val loader = MokOperationsLoaderImpl()

    private fun fixture() = ChronicleFixture(dir.resolve("dump.sqlite"))

    @Test
    fun `reads rows in uid order with 64-bit uids`() {
        val dump = loader.load(
            fixture()
                .row(10, """{"uid":1}""", uid = 5)
                .row(20, """{"uid":2}""", uid = 3)
                .row(12, """{"uid":3}""", uid = 3_000_000_000)
                .write()
        )

        assertEquals(listOf(3L, 5L, 3_000_000_000L), dump.operations.map { it.num })
    }

    @Test
    fun `passes unknown types, a null type and null params through untouched`() {
        val dump = loader.load(
            fixture()
                .row(90, """{"name":"photo.jpg"}""")
                .row(null, """{"uid":1}""")
                .row(10, null)
                .write()
        )

        assertEquals(
            listOf(
                RawMokOperation(1, 90, """{"name":"photo.jpg"}"""),
                RawMokOperation(2, null, """{"uid":1}"""),
                RawMokOperation(3, 10, null),
            ),
            dump.operations,
        )
    }

    @Test
    fun `a sequence matching max uid gives no warning`() {
        val dump = loader.load(fixture().row(1, """{"version":3}""").row(20, """{"uid":1}""").write())

        assertNull(dump.renumbering)
    }

    @Test
    fun `a sequence disagreeing with max uid warns and still reads every row`() {
        val dump = loader.load(fixture().row(1, """{"version":3}""").row(20, """{"uid":1}""").sequence(5448).write())

        assertEquals(RenumberingWarning(sequence = 5448, maxUid = 2), dump.renumbering)
        assertEquals(2, dump.operations.size)
    }

    @Test
    fun `a missing sequence row warns`() {
        val dump = loader.load(fixture().row(20, """{"uid":1}""").withoutSequenceRow().write())

        assertEquals(RenumberingWarning(sequence = null, maxUid = 1), dump.renumbering)
    }

    @Test
    fun `a dump with no sequence table at all warns`() {
        val file = dir.resolve("plain.sqlite")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use {
            it.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE Chronicle(uid INTEGER PRIMARY KEY, badge INTEGER, type INTEGER, params TEXT)")
                s.executeUpdate("""INSERT INTO Chronicle (uid, badge, type, params) VALUES (4, 0, 20, '{"uid":1}')""")
            }
        }

        assertEquals(RenumberingWarning(sequence = null, maxUid = 4), loader.load(file).renumbering)
    }

    @Test
    fun `an empty chronicle gives no rows and no warning`() {
        val dump = loader.load(fixture().write())

        assertTrue(dump.operations.isEmpty())
        assertNull(dump.renumbering)
    }

    @Test
    fun `a file without a Chronicle table fails`() {
        val file = dir.resolve("other.sqlite")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use {
            it.createStatement().use { s -> s.executeUpdate("CREATE TABLE something (x INTEGER)") }
        }

        assertFailsWith<InvalidMokDumpException> { loader.load(file) }
    }

    @Test
    fun `a wrong path fails without creating a file`() {
        val missing = dir.resolve("missing.sqlite")

        assertFailsWith<InvalidMokDumpException> { loader.load(missing) }
        assertFalse(Files.exists(missing))
    }
}
