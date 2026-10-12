package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.CategoryType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

internal class AccountState(
    val id: UUID,
    val uid: Long,
    var name: String,
    var currency: String,
    var balance: BigDecimal,
    val initialBalance: BigDecimal,
) {
    var initialBalanceAt: LocalDateTime? = null
    var archived = false
    var hasOperations = false
}

internal class OperationState(
    val id: UUID,
    val uid: Long,
    var date: LocalDateTime,
    val kind: CategoryType,
    var account: AccountState,
    var category: Long?,
    var money: BigDecimal,
    var note: String,
) {
    fun signed() = kind.signed(money)
}

internal class TransferState(
    val id: UUID,
    val uid: Long,
    var date: LocalDateTime,
    var source: AccountState,
    var target: AccountState,
    var money: BigDecimal,
    var moneyTo: BigDecimal?,
    var note: String,
) {
    var sourceOrphaned = false
    var targetOrphaned = false

    fun targetAmount(): BigDecimal = moneyTo?.takeIf { it.signum() >= 0 } ?: money

    fun apply() {
        if (!sourceOrphaned) source.balance -= money
        if (!targetOrphaned) target.balance += targetAmount()
    }

    fun rollback() {
        if (!sourceOrphaned) source.balance += money
        if (!targetOrphaned) target.balance -= targetAmount()
    }
}

internal class CategoryState(
    val id: UUID,
    val uid: Long,
    val isGroup: Boolean,
    var name: String,
    val kind: CategoryType,
    var parent: Long?,
)

internal class AnchorState(val account: AccountState, val value: BigDecimal) {
    var stamp: LocalDateTime? = null
}
