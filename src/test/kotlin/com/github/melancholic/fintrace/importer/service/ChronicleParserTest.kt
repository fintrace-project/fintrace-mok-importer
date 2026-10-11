package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.domain.*
import com.github.melancholic.fintrace.importer.exception.InvalidChronicleException
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChronicleParserTest {

    private val parser = ChronicleParserImpl(JsonMapper.builder().build())

    private fun parse(vararg rows: Pair<Int?, String?>, renumbering: RenumberingWarning? = null) =
        parser.parse(
            ChronicleDump(
                rows.mapIndexed { index, (type, params) -> RawMokOperation(index + 1L, type, params) },
                renumbering,
            )
        )

    private inline fun <reified T : MokOperation> single(vararg rows: Pair<Int?, String?>): T =
        assertIs<T>(parse(*rows).events.single())

    private fun operation(money: String) =
        10 to """{"uid":1,"opType":1,"accUid":1,"catUid":5,"money":$money,"date":"2025.01.05 23:06:24"}"""

    @Test
    fun `money is read from JSON ints and floats alike, at two decimals`() {
        assertEquals(BigDecimal("100.00"), single<AddNewTransaction>(operation("100")).money)
        assertEquals(BigDecimal("100.50"), single<AddNewTransaction>(operation("100.5")).money)
    }

    @Test
    fun `money rounds as the vendor's double arithmetic does, not as a decimal would`() {
        assertEquals(BigDecimal("1.00"), single<AddNewTransaction>(operation("1.005")).money)
        assertEquals(BigDecimal("0.13"), single<AddNewTransaction>(operation("0.125")).money)
    }

    @Test
    fun `an account balance may be negative`() {
        val account = single<AddedNewAccount>(20 to """{"uid":1,"name":"Card","money":-250.75,"currency":978}""")

        assertEquals(BigDecimal("-250.75"), account.money)
    }

    @Test
    fun `uids and references survive past 2^31`() {
        val operation = single<AddNewTransaction>(
            10 to """{"uid":3000000000,"opType":2,"accUid":3000000001,"catUid":3000000002,"money":1,"date":"2025.01.05 23:06:24"}"""
        )

        assertEquals(3_000_000_000L, operation.uid)
        assertEquals(3_000_000_001L, operation.account)
        assertEquals(3_000_000_002L, operation.category)
    }

    @Test
    fun `an operation keeps its kind and category`() {
        val operation = single<AddNewTransaction>(operation("1"))

        assertEquals(CategoryType.EXPENSE, operation.opType)
        assertEquals(5L, operation.category)
    }

    @Test
    fun `currency stays the raw code, absent distinct from zero`() {
        fun currencyOf(json: String) = single<AddedNewAccount>(20 to json).currency

        assertEquals(840, currencyOf("""{"uid":1,"name":"A","money":0,"currency":840}"""))
        assertEquals(0, currencyOf("""{"uid":1,"name":"A","money":0,"currency":0}"""))
        assertNull(currencyOf("""{"uid":1,"name":"A","money":0}"""))
    }

    @Test
    fun `unsupported rows are counted per code and skipped without parsing their params`() {
        val parsed = parse(
            90 to """{"name":"photo.jpg"}""",
            90 to "not json at all",
            23 to """{"uid":1,"fromIndex":0,"toIndex":1}""",
            100 to null,
            null to null,
            999 to "{}",
            20 to """{"uid":1,"name":"Cash","money":0,"currency":978}""",
        )

        assertIs<AddedNewAccount>(parsed.events.single())
        assertEquals(
            listOf("type=90" to 2L, "type=23" to 1L, "type=100" to 1L, "type=null" to 1L, "type=999" to 1L),
            parsed.diagnostics.map { it.detail to it.count },
        )
        assertTrue(parsed.diagnostics.all {
            it.code == Diagnostic.Code.UNSUPPORTED_RECORD && it.severity == Diagnostic.Severity.WARNING
        })
    }

    @Test
    fun `events keep the dump's uid order`() {
        val parsed = parse(
            20 to """{"uid":1,"name":"Cash","money":0,"currency":978}""",
            90 to "{}",
            operation("1"),
            12 to """{"uid":1,"moneyBack":1}""",
        )

        assertEquals(listOf(1L, 3L, 4L), parsed.events.map { it.num })
    }

    @Test
    fun `the version row is read out of the events`() {
        val parsed = parse(1 to """{"version":3}""", operation("1"))

        assertEquals(3, parsed.version)
        assertIs<AddNewTransaction>(parsed.events.single())
    }

    @Test
    fun `a log without a version row has no version`() {
        assertNull(parse(operation("1")).version)
    }

    @Test
    fun `a newer chronicle version is refused, as the vendor refuses it`() {
        assertFailsWith<InvalidChronicleException> { parse(1 to """{"version":4}""") }
    }

    @Test
    fun `unparseable params on a supported row are refused`() {
        assertFailsWith<InvalidChronicleException> { parse(10 to "{oops") }
    }

    @Test
    fun `missing params on a supported row are refused`() {
        assertFailsWith<InvalidChronicleException> { parse(10 to null) }
    }

    @Test
    fun `a supported row missing a required key is refused`() {
        assertFailsWith<InvalidChronicleException> { parse(20 to """{"uid":1}""") }
    }

    @Test
    fun `moneyTo on a transfer update keeps absent, -1 and a value apart`() {
        fun moneyToOf(json: String) = single<UpdatedTransfer>(51 to json).moneyTo

        assertNull(moneyToOf("""{"uid":1,"note":"edited"}"""))
        assertEquals(BigDecimal("-1.00"), moneyToOf("""{"uid":1,"moneyTo":-1}"""))
        assertEquals(BigDecimal("92.40"), moneyToOf("""{"uid":1,"moneyTo":92.4}"""))
    }

    @Test
    fun `a note-only transfer update leaves every other field unset`() {
        val update = single<UpdatedTransfer>(51 to """{"uid":1,"note":"edited"}""")

        assertEquals("edited", update.note)
        assertNull(update.money)
        assertNull(update.accFrom)
        assertNull(update.accTo)
        assertNull(update.date)
    }

    @Test
    fun `the operation update gates are read as given`() {
        val gated = single<UpdateTransaction>(11 to """{"uid":1,"money":5,"moneyBack":1,"accUid":2,"moneyBack2":0}""")
        val ungated = single<UpdateTransaction>(11 to """{"uid":1,"note":"x"}""")

        assertEquals(true, gated.moneyBack)
        assertEquals(false, gated.moneyBack2)
        assertNull(ungated.moneyBack)
        assertNull(ungated.moneyBack2)
    }

    @Test
    fun `category events carry their group and kind`() {
        val parsed = parse(
            30 to """{"uid":3,"name":"Home","opType":1}""",
            40 to """{"uid":7,"name":"Rent","opType":1,"icon":"item7.png","groupUid":3,"color":6464114}""",
            45 to """{"uid":7,"isGroup":0,"groupUid":3,"fromIndex":0,"newGroupUid":1,"toIndex":2}""",
            45 to """{"uid":3,"isGroup":true,"groupUid":1,"fromIndex":0,"newGroupUid":1,"toIndex":1}""",
            42 to """{"uid":7,"moneyBack":1}""",
        )

        val group = assertIs<AddedNewCategoriesGroup>(parsed.events[0])
        val category = assertIs<AddedNewCategory>(parsed.events[1])
        val moveCategory = assertIs<MovedItem>(parsed.events[2])
        val moveGroup = assertIs<MovedItem>(parsed.events[3])
        val delete = assertIs<DeletedCategory>(parsed.events[4])

        assertEquals(CategoryType.EXPENSE, group.opType)
        assertEquals(3L, category.group)
        assertEquals(false, moveCategory.isGroup)
        assertEquals(1L, moveCategory.newGroup)
        assertEquals(true, moveGroup.isGroup)
        assertEquals(true, delete.moneyBack)
    }

    @Test
    fun `the renumbering warning passes through`() {
        val warning = RenumberingWarning(sequence = 5448, maxUid = 50)

        assertEquals(warning, parse(operation("1"), renumbering = warning).renumbering)
    }
}
