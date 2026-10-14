package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.config.ImporterConstants.IMPORTER_NAME
import com.github.melancholic.fintrace.importer.contract.*
import com.github.melancholic.fintrace.importer.domain.*
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component

interface ImportRequestFactory {
    fun buildImportRequest(chronicleSnapshot: ChronicleSnapshot): ImportEnvelopRequest
}

@Component
class ImportRequestFactoryImpl(
    private val build: BuildProperties
) : ImportRequestFactory {

    override fun buildImportRequest(chronicleSnapshot: ChronicleSnapshot): ImportEnvelopRequest = ImportEnvelopRequest(
        importerName = IMPORTER_NAME,
        importerVersion = build.version!!,
        payload = buildPayload(chronicleSnapshot)
    )

    private fun buildPayload(chronicleSnapshot: ChronicleSnapshot): ImportPayloadRequest {

        return ImportPayloadRequest(
            accounts = accounts(chronicleSnapshot.accounts),
            categories = categories(chronicleSnapshot.categories),
            operations = operations(chronicleSnapshot.operations),
            transfers = transfers(chronicleSnapshot.transfers),
            balanceAnchors = balanceAnchors(chronicleSnapshot.anchors),
            diagnostics = diagnostics(chronicleSnapshot.diagnostics, chronicleSnapshot.renumbering),
        )
    }

    private fun accounts(accounts: List<SnapshotAccount>) = accounts
        .map {
            ImportAccountRequest(
                id = it.id,
                externalRef = "MOK-ACCOUNT#${it.uid}",
                name = it.name,
                currency = it.currency,
                initialBalance = it.initialBalance,
                initialBalanceAt = it.initialBalanceAt,
                archived = it.archived
            )
        }

    private fun categories(categories: List<SnapshotCategory>) = categories
        .map {
            ImportCategoryRequest(
                id = it.id,
                externalRef = "${if (it.isGroup) "MOK-GROUP" else "MOK-CATEGORY"}#${it.uid}",                name = it.name,
                kind = when (it.kind) {
                    CategoryType.INCOME -> ImportCategoryRequest.Kind.INCOME
                    CategoryType.EXPENSE -> ImportCategoryRequest.Kind.EXPENSE
                },
                parentId = it.parentId,
            )
        }

    private fun operations(operations: List<SnapshotOperation>) = operations
        .map {
            ImportOperationRequest(
                id = it.id,
                externalRef = "MOK-OPERATION#${it.uid}",
                occurredAt = it.occurredAt,
                amount = it.amount,
                kind = when (it.kind) {
                    CategoryType.INCOME -> ImportOperationRequest.Kind.INCOME
                    CategoryType.EXPENSE -> ImportOperationRequest.Kind.EXPENSE
                },
                accountId = it.accountId,
                categoryId = it.categoryId,
                comment = it.comment,
            )
        }

    private fun transfers(transfers: List<SnapshotTransfer>) = transfers
        .map {
            ImportTransferRequest(
                id = it.id,
                externalRef = "MOK-TRANSFER#${it.uid}",
                occurredAt = it.occurredAt,
                source = ImportTransferLegRequest(
                    accountId = it.source.accountId,
                    amount = it.source.amount,
                ),
                target = ImportTransferLegRequest(
                    accountId = it.target.accountId,
                    amount = it.target.amount,
                ),
                comment = it.comment,
            )
        }

    private fun balanceAnchors(anchors: List<SnapshotAnchor>) = anchors
        .map {
            ImportBalanceAnchorRequest(
                id = it.id,
                accountId = it.accountId,
                occurredAt = it.occurredAt,
                value = it.value,
            )
        }

    private fun diagnostics(diagnostics: List<Diagnostic>, renumbering: RenumberingWarning?): List<ImportDiagnosticRequest> {
        val result = diagnostics
            .map {
                ImportDiagnosticRequest(
                    severity = when (it.severity) {
                        Diagnostic.Severity.WARNING -> ImportDiagnosticRequest.Severity.WARNING
                        Diagnostic.Severity.ERROR -> ImportDiagnosticRequest.Severity.ERROR
                    },
                    code = when (it.code) {
                        Diagnostic.Code.UNSUPPORTED_RECORD -> ImportDiagnosticRequest.Code.UNSUPPORTED_RECORD
                        Diagnostic.Code.UNKNOWN_CURRENCY -> ImportDiagnosticRequest.Code.UNKNOWN_CURRENCY
                        Diagnostic.Code.UNRESOLVED_REFERENCE -> ImportDiagnosticRequest.Code.UNRESOLVED_REFERENCE
                        Diagnostic.Code.SOURCE_ANOMALY -> ImportDiagnosticRequest.Code.SOURCE_ANOMALY
                    },
                    count = it.count,
                    detail = it.detail
                )
            }
            .toMutableList()

        renumbering?.let {
            result.add(
                ImportDiagnosticRequest(
                    severity = ImportDiagnosticRequest.Severity.WARNING,
                    code = ImportDiagnosticRequest.Code.SOURCE_ANOMALY,
                    count = 1,
                    detail = "Inconsistency was detected between the sequence's last value (${renumbering.sequence}) and the maximum ID (${renumbering.maxUid})"
                )
            )
        }
        return result
    }
}