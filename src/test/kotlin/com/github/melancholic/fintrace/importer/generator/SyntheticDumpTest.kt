package com.github.melancholic.fintrace.importer.generator

import com.github.melancholic.fintrace.importer.domain.ChronicleSnapshot
import com.github.melancholic.fintrace.importer.service.ChronicleParser
import com.github.melancholic.fintrace.importer.service.MokOperationsLoader
import com.github.melancholic.fintrace.importer.service.interpreter.ChronicleInterpreter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class SyntheticDumpTest(
    @Autowired private val loader: MokOperationsLoader,
    @Autowired private val parser: ChronicleParser,
    @Autowired private val interpreter: ChronicleInterpreter,
) {

    private val dump = SyntheticDumpGenerator(SyntheticDumpSize.S).generate()

    @Test
    fun `the committed S dump is what the generator writes`() {
        assertEquals(dump.toSql(), resource(SyntheticDump.sqlFileName(SyntheticDumpSize.S)), STALE)
        assertEquals(dump.toExpectedJson(), resource(SyntheticDump.expectedFileName(SyntheticDumpSize.S)), STALE)
    }

    @Test
    fun `the committed S dump imports with no diagnostics and the generator's balances`(@TempDir directory: Path) {
        val file = SyntheticDump.buildSqlite(
            resource(SyntheticDump.sqlFileName(SyntheticDumpSize.S)),
            directory.resolve(SyntheticDump.mokFileName(SyntheticDumpSize.S)),
        )

        val snapshot = interpreter.interpretChronicle(parser.parse(loader.load(file)), NOW, "EUR")

        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertNull(snapshot.renumbering)
        val expected = dump.expected
        assertEquals(expected.accounts.size, snapshot.accounts.size)
        assertEquals(expected.categories, snapshot.categories.size)
        assertEquals(expected.operations, snapshot.operations.size)
        assertEquals(expected.transfers, snapshot.transfers.size)
        expected.accounts.forEach { account ->
            val actual = snapshot.accounts.single { it.name == account.name }
            assertEquals(account.uid, actual.uid, account.name)
            assertEquals(account.currency, actual.currency, account.name)
            assertEquals(account.archived, actual.archived, account.name)
            assertEquals(account.balance, snapshot.finalBalance(actual.id), account.name)
        }
    }

    private fun ChronicleSnapshot.finalBalance(accountId: UUID) =
        anchors.filter { it.accountId == accountId }.maxBy { it.occurredAt }.value

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/synthetic/$name")) { "missing /synthetic/$name: $STALE" }.readText()

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)
        const val STALE = "the committed synthetic dump is stale - run ./gradlew generateSyntheticDumps and commit the result"
    }
}
