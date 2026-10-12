package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.*
import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.service.UUIDGenerator
import org.springframework.stereotype.Component

internal sealed interface TransferApplier {
    fun createTransfer(context: ChronicleSnapshotContext, event: AddedNewTransfer)
    fun updateTransfer(context: ChronicleSnapshotContext, event: UpdatedTransfer)
    fun deleteTransfer(context: ChronicleSnapshotContext, event: DeletedTransfer)
    fun emitTransfers(context: ChronicleSnapshotContext): List<SnapshotTransfer>
}

@Component
internal class TransferApplierImpl(
    private val ids: UUIDGenerator,
) : AbstractApplier(), TransferApplier {

    override fun createTransfer(context: ChronicleSnapshotContext, event: AddedNewTransfer) {
        if (context.transfers.containsKey(event.uid)) {
            count(context, Code.SOURCE_ANOMALY, "duplicate transfer creation ignored")
        } else {
            val source = referencedAccount(context, event.accFrom, "transfer")
            val target = source?.let { referencedAccount(context, event.accTo, "transfer") }
            if (source != null && target != null) {
                val transfer = TransferState(
                    id = ids.nextUUID(),
                    uid = event.uid,
                    date = event.date ?: context.maxBusinessDate ?: context.now,
                    source = source,
                    target = target,
                    money = event.money,
                    moneyTo = event.moneyTo,
                    note = event.note,
                )
                transfer.apply()
                context.transfers[event.uid] = transfer
            }
        }
    }

    override fun updateTransfer(context: ChronicleSnapshotContext, event: UpdatedTransfer) {
        val transfer = context.transfers[event.uid]
        if (transfer == null) {
            count(context, Code.SOURCE_ANOMALY, "update of an unknown transfer ignored")
        } else {
            transfer.rollback()
            event.money?.let { transfer.money = it }
            transfer.moneyTo = event.moneyTo
            event.accFrom?.let { uid ->
                referencedAccount(context, uid, "transfer update")
                    ?.let { transfer.source = it; transfer.sourceOrphaned = false }
            }
            event.accTo?.let { uid ->
                referencedAccount(context, uid, "transfer update")
                    ?.let { transfer.target = it; transfer.targetOrphaned = false }
            }
            event.note?.let { transfer.note = it }
            event.date?.let { transfer.date = it }
            transfer.apply()
        }
    }

    override fun deleteTransfer(context: ChronicleSnapshotContext, event: DeletedTransfer) {
        val transfer = context.transfers.remove(event.uid)
        if (transfer == null) {
            count(context, Code.SOURCE_ANOMALY, "deletion of an unknown transfer ignored")
        } else {
            transfer.rollback()
        }
    }

    override fun emitTransfers(context: ChronicleSnapshotContext) = context.transfers.values.mapNotNull { transfer ->
        val targetAmount = transfer.targetAmount()
        when {
            transfer.source === transfer.target -> {
                count(context, Code.SOURCE_ANOMALY, "transfer within one account dropped")
                null
            }

            transfer.money.signum() <= 0 || targetAmount.signum() <= 0 -> {
                count(context, Code.SOURCE_ANOMALY, "transfer without a positive amount dropped")
                null
            }

            else -> SnapshotTransfer(
                id = transfer.id,
                uid = transfer.uid,
                occurredAt = transfer.date,
                source = SnapshotTransferLeg(transfer.source.id, transfer.money),
                target = SnapshotTransferLeg(transfer.target.id, targetAmount),
                comment = comment(context, transfer.note),
            )
        }
    }
}
