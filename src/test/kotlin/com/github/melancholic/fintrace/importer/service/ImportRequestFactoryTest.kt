package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.contract.CategoryKind
import com.github.melancholic.fintrace.importer.contract.ImportDiagnosticCode
import com.github.melancholic.fintrace.importer.contract.ImportDiagnosticSeverity
import com.github.melancholic.fintrace.importer.contract.OperationKind
import com.github.melancholic.fintrace.importer.domain.*
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals

class ImportRequestFactoryTest {

    private val factory = ImportRequestFactoryImpl(BuildProperties(Properties().apply { setProperty("version", "1.2.3") }))

    private val at: LocalDateTime = LocalDateTime.of(2026, 1, 1, 12, 0)

    private fun snapshot(
        accounts: List<SnapshotAccount> = emptyList(),
        categories: List<SnapshotCategory> = emptyList(),
        operations: List<SnapshotOperation> = emptyList(),
        transfers: List<SnapshotTransfer> = emptyList(),
        anchors: List<SnapshotAnchor> = emptyList(),
        diagnostics: List<Diagnostic> = emptyList(),
        renumbering: RenumberingWarning? = null,
    ) = ChronicleSnapshot(accounts, categories, operations, transfers, anchors, diagnostics, renumbering)

    private fun category(uid: Long, isGroup: Boolean, kind: CategoryType = CategoryType.EXPENSE, parentId: UUID? = null) =
        SnapshotCategory(UUID.randomUUID(), uid, isGroup, "Category $uid", kind, parentId)

    @Test
    fun `the envelope names the importer and carries the build's version`() {
        val request = factory.buildImportRequest(snapshot())

        assertEquals("fintrace-mok-importer", request.importerName)
        assertEquals("1.2.3", request.importerVersion)
    }

    @Test
    fun `empty sections are sent as empty lists, never omitted`() {
        val payload = factory.buildImportRequest(snapshot()).payload

        assertEquals(emptyList(), payload.accounts)
        assertEquals(emptyList(), payload.categories)
        assertEquals(emptyList(), payload.operations)
        assertEquals(emptyList(), payload.transfers)
        assertEquals(emptyList(), payload.balanceAnchors)
        assertEquals(emptyList(), payload.diagnostics)
    }

    @Test
    fun `a group and a category with the same uid get distinct refs`() {
        val group = category(3, isGroup = true)
        val child = category(3, isGroup = false, parentId = group.id)

        val categories = factory.buildImportRequest(snapshot(categories = listOf(group, child))).payload.categories!!

        assertEquals(listOf("MOK-GROUP#3", "MOK-CATEGORY#3"), categories.map { it.externalRef })
        assertEquals(listOf(null, group.id), categories.map { it.parentId })
    }

    @Test
    fun `a category's kind maps to its contract kind`() {
        val categories = factory.buildImportRequest(
            snapshot(categories = listOf(category(1, false, CategoryType.INCOME), category(2, false, CategoryType.EXPENSE)))
        ).payload.categories!!

        assertEquals(listOf(CategoryKind.INCOME, CategoryKind.EXPENSE), categories.map { it.kind })
    }

    @Test
    fun `an account keeps its id, opening balance and archived flag`() {
        val account = SnapshotAccount(UUID.randomUUID(), 7, "Cash", "EUR", BigDecimal("100.50"), at, archived = true)

        val request = factory.buildImportRequest(snapshot(accounts = listOf(account))).payload.accounts!!.single()

        assertEquals(account.id, request.id)
        assertEquals("MOK-ACCOUNT#7", request.externalRef)
        assertEquals("Cash", request.name)
        assertEquals("EUR", request.currency)
        assertEquals(BigDecimal("100.50"), request.initialBalance)
        assertEquals(at, request.initialBalanceAt)
        assertEquals(true, request.archived)
    }

    @Test
    fun `an operation keeps its absolute amount and maps its kind`() {
        val accountId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val operations = listOf(
            SnapshotOperation(UUID.randomUUID(), 11, at, BigDecimal("30"), CategoryType.EXPENSE, accountId, categoryId, "lunch"),
            SnapshotOperation(UUID.randomUUID(), 12, at, BigDecimal("50"), CategoryType.INCOME, accountId, null, null),
        )

        val requests = factory.buildImportRequest(snapshot(operations = operations)).payload.operations!!

        assertEquals(listOf("MOK-OPERATION#11", "MOK-OPERATION#12"), requests.map { it.externalRef })
        assertEquals(listOf(BigDecimal("30"), BigDecimal("50")), requests.map { it.amount })
        assertEquals(listOf(OperationKind.EXPENSE, OperationKind.INCOME), requests.map { it.kind })
        assertEquals(listOf(categoryId, null), requests.map { it.categoryId })
        assertEquals(listOf("lunch", null), requests.map { it.comment })
    }

    @Test
    fun `a transfer is one object whose legs keep their accounts and amounts`() {
        val transfer = SnapshotTransfer(
            UUID.randomUUID(), 5, at,
            SnapshotTransferLeg(UUID.randomUUID(), BigDecimal("100")),
            SnapshotTransferLeg(UUID.randomUUID(), BigDecimal("92.5")),
            "exchange",
        )

        val request = factory.buildImportRequest(snapshot(transfers = listOf(transfer))).payload.transfers!!.single()

        assertEquals(transfer.id, request.id)
        assertEquals("MOK-TRANSFER#5", request.externalRef)
        assertEquals(transfer.source.accountId, request.source.accountId)
        assertEquals(BigDecimal("100"), request.source.amount)
        assertEquals(transfer.target.accountId, request.target.accountId)
        assertEquals(BigDecimal("92.5"), request.target.amount)
    }

    @Test
    fun `anchors keep the snapshot's order`() {
        val accountId = UUID.randomUUID()
        val anchors = listOf(
            SnapshotAnchor(UUID.randomUUID(), accountId, at, BigDecimal("10")),
            SnapshotAnchor(UUID.randomUUID(), accountId, at.plusDays(1), BigDecimal("20")),
        )

        val requests = factory.buildImportRequest(snapshot(anchors = anchors)).payload.balanceAnchors!!

        assertEquals(anchors.map { it.id }, requests.map { it.id })
        assertEquals(anchors.map { it.value }, requests.map { it.value })
    }

    @Test
    fun `every diagnostic severity and code maps to its contract value by name`() {
        val diagnostics = Diagnostic.Severity.entries.flatMap { severity ->
            Diagnostic.Code.entries.map { code -> Diagnostic(severity, code, 1) }
        }

        val requests = factory.buildImportRequest(snapshot(diagnostics = diagnostics)).payload.diagnostics!!

        assertEquals(diagnostics.map { it.severity.name to it.code.name }, requests.map { it.severity.name to it.code.name })
    }

    @Test
    fun `diagnostics are passed through and a renumbered dump adds one source anomaly`() {
        val diagnostics = listOf(Diagnostic(Diagnostic.Severity.WARNING, Diagnostic.Code.UNSUPPORTED_RECORD, 4, "type=110"))

        val requests = factory.buildImportRequest(
            snapshot(diagnostics = diagnostics, renumbering = RenumberingWarning(sequence = 900, maxUid = 950))
        ).payload.diagnostics!!

        assertEquals(2, requests.size)
        with(requests[0]) {
            assertEquals(ImportDiagnosticCode.UNSUPPORTED_RECORD, code)
            assertEquals(4, count)
            assertEquals("type=110", detail)
        }
        with(requests[1]) {
            assertEquals(ImportDiagnosticSeverity.WARNING, severity)
            assertEquals(ImportDiagnosticCode.SOURCE_ANOMALY, code)
            assertEquals(1, count)
        }
    }
}
