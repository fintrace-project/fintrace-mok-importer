package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.AddNewTransaction
import com.github.melancholic.fintrace.importer.domain.DeleteTransaction
import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.domain.SnapshotOperation
import com.github.melancholic.fintrace.importer.domain.UpdateTransaction
import com.github.melancholic.fintrace.importer.service.UUIDGenerator
import org.springframework.stereotype.Component
import java.util.*

internal sealed interface OperationApplier {
    fun createOperation(context: ChronicleSnapshotContext, event: AddNewTransaction)
    fun updateOperation(context: ChronicleSnapshotContext, event: UpdateTransaction)
    fun deleteOperation(context: ChronicleSnapshotContext, event: DeleteTransaction)
    fun emitOperations(context: ChronicleSnapshotContext): List<SnapshotOperation>
}

@Component
internal class OperationApplierImpl(
    private val ids: UUIDGenerator,
) : AbstractApplier(), OperationApplier {

    override fun createOperation(context: ChronicleSnapshotContext, event: AddNewTransaction) {
        if (context.operations.containsKey(event.uid)) {
            count(context, Code.SOURCE_ANOMALY, "duplicate operation creation ignored")
        } else {
            val account = referencedAccount(context, event.account, "operation")
            if (account != null) {
                val operation = OperationState(
                    id = ids.nextUUID(),
                    uid = event.uid,
                    date = event.date!!,
                    kind = event.opType,
                    account = account,
                    category = event.category,
                    money = event.money,
                    note = event.note,
                )
                account.balance += operation.signed()
                account.hasOperations = true
                context.operations[event.uid] = operation
            }
        }
    }

    override fun updateOperation(context: ChronicleSnapshotContext, event: UpdateTransaction) {
        val operation = context.operations[event.uid]
        if (operation == null) {
            count(context, Code.SOURCE_ANOMALY, "update of an unknown operation ignored")
        } else {
            event.date?.let { operation.date = it }
            event.money?.let { money ->
                if (event.moneyBack == true) {
                    operation.account.balance += operation.kind.signed(money - operation.money)
                }
                operation.money = money
            }
            event.account?.let { uid ->
                val target = referencedAccount(context, uid, "operation move")
                if (target != null) {
                    if (event.moneyBack2 == true) {
                        operation.account.balance -= operation.signed()
                        target.balance += operation.signed()
                    }
                    operation.account = target
                    target.hasOperations = true
                }
            }
            event.category?.let { operation.category = it }
            event.note?.let { operation.note = it }
        }
    }

    override fun deleteOperation(context: ChronicleSnapshotContext, event: DeleteTransaction) {
        val operation = context.operations.remove(event.uid)
        if (operation == null) {
            count(context, Code.SOURCE_ANOMALY, "deletion of an unknown operation ignored")
        } else if (event.moneyBack == true) {
            operation.account.balance -= operation.signed()
        }
    }

    override fun emitOperations(context: ChronicleSnapshotContext) = context.operations.values.mapNotNull { operation ->
        if (operation.money.signum() <= 0) {
            count(context, Code.SOURCE_ANOMALY, "operation without a positive amount dropped")
            null
        } else {
            SnapshotOperation(
                id = operation.id,
                uid = operation.uid,
                occurredAt = operation.date,
                amount = operation.money,
                kind = operation.kind,
                accountId = operation.account.id,
                categoryId = categoryOf(context, operation),
                comment = comment(context, operation.note),
            )
        }
    }

    private fun categoryOf(context: ChronicleSnapshotContext, operation: OperationState): UUID? {
        val uid = operation.category
        val category = uid?.let { context.categories[it] }
        return when {
            uid == null -> null
            category == null -> {
                count(context, Code.UNRESOLVED_REFERENCE, "operation category no longer exists")
                null
            }
            category.kind != operation.kind -> {
                count(context, Code.SOURCE_ANOMALY, "operation filed under a category of the other kind")
                null
            }
            else -> category.id
        }
    }
}
