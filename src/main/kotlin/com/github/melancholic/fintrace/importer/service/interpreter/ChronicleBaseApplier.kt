package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.*
import org.springframework.stereotype.Component

@Component
internal class ChronicleBaseApplier(
    val accountApplier: AccountApplier,
    val operationApplier: OperationApplier,
    val transferApplier: TransferApplier,
    val categoryApplier: CategoryApplier,
) {
    fun apply(context: ChronicleSnapshotContext, event: MokOperation) {
        when (event) {
            is AddedNewAccount -> accountApplier.createAccount(context, event)
            is UpdatedAccount -> accountApplier.updateAccount(context, event)
            is DeletedAccount -> accountApplier.deleteAccount(context, event)
            is AccountMoneyVerification -> accountApplier.assignBalance(context, event.uid, event.money)

            is AddNewTransaction -> operationApplier.createOperation(context, event)
            is UpdateTransaction -> operationApplier.updateOperation(context, event)
            is DeleteTransaction -> operationApplier.deleteOperation(context, event)

            is AddedNewTransfer -> transferApplier.createTransfer(context, event)
            is UpdatedTransfer -> transferApplier.updateTransfer(context, event)
            is DeletedTransfer -> transferApplier.deleteTransfer(context, event)

            is AddedNewCategoriesGroup -> categoryApplier.createGroupCategory(context, event)
            is UpdatedCategoriesGroup -> categoryApplier.updateGroupCategory(context, event)
            is DeletedCategoriesGroup -> categoryApplier.removeGroupCategory(context, event)

            is AddedNewCategory -> categoryApplier.createCategory(context, event)
            is UpdatedCategory -> categoryApplier.updateCategory(context, event)
            is DeletedCategory -> categoryApplier.removeCategory(context, event)
            is MovedItem -> categoryApplier.moveCategory(context, event)

            else -> Unit
        }
    }
}
