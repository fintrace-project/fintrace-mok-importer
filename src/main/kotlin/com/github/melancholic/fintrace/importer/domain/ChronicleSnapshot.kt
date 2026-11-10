package com.github.melancholic.fintrace.importer.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class ChronicleSnapshot(
    val accounts: List<SnapshotAccount>,
    val categories: List<SnapshotCategory>,
    val operations: List<SnapshotOperation>,
    val transfers: List<SnapshotTransfer>,
    val anchors: List<SnapshotAnchor>,
    val diagnostics: List<Diagnostic>,
    val renumbering: RenumberingWarning?,
)

data class SnapshotAccount(
    val id: UUID,
    val uid: Long,
    val name: String,
    val currency: String,
    val initialBalance: BigDecimal,
    val initialBalanceAt: LocalDateTime,
    val archived: Boolean,
)

data class SnapshotCategory(
    val id: UUID,
    val uid: Long,
    val isGroup: Boolean,
    val name: String,
    val kind: CategoryType,
    val parentId: UUID?,
)

sealed interface BusinessDated {
    val uid: Long
    val occurredAt: LocalDateTime
}

data class SnapshotOperation(
    val id: UUID,
    override val uid: Long,
    override val occurredAt: LocalDateTime,
    val amount: BigDecimal,
    val kind: CategoryType,
    val accountId: UUID,
    val categoryId: UUID?,
    val comment: String?,
) : BusinessDated

data class SnapshotTransfer(
    val id: UUID,
    override val uid: Long,
    override val occurredAt: LocalDateTime,
    val source: SnapshotTransferLeg,
    val target: SnapshotTransferLeg,
    val comment: String?,
) : BusinessDated

data class SnapshotTransferLeg(
    val accountId: UUID,
    val amount: BigDecimal,
)

data class SnapshotAnchor(
    val id: UUID,
    val accountId: UUID,
    val occurredAt: LocalDateTime,
    val value: BigDecimal,
)