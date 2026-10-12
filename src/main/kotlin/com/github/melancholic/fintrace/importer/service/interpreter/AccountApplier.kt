package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.AddedNewAccount
import com.github.melancholic.fintrace.importer.domain.DeletedAccount
import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.domain.SnapshotAccount
import com.github.melancholic.fintrace.importer.domain.UpdatedAccount
import com.github.melancholic.fintrace.importer.service.UUIDGenerator
import org.springframework.stereotype.Component
import java.math.BigDecimal

internal sealed interface AccountApplier {
    fun createAccount(context: ChronicleSnapshotContext, event: AddedNewAccount)
    fun updateAccount(context: ChronicleSnapshotContext, event: UpdatedAccount)
    fun deleteAccount(context: ChronicleSnapshotContext, event: DeletedAccount)
    fun assignBalance(context: ChronicleSnapshotContext, uid: Long, money: BigDecimal)
    fun emitAccounts(context: ChronicleSnapshotContext): List<SnapshotAccount>
}

@Component
internal class AccountApplierImpl(
    private val ids: UUIDGenerator,
) : AbstractApplier(), AccountApplier {

    override fun createAccount(context: ChronicleSnapshotContext, event: AddedNewAccount) {
        if (context.accounts.containsKey(event.uid)) {
            count(context, Code.SOURCE_ANOMALY, "duplicate account creation ignored")
        } else {
            val currency = resolveCurrency(context, event.currency)
            if (currency == null) {
                context.skippedAccounts.add(event.uid)
                count(context, Code.UNKNOWN_CURRENCY, "account skipped")
            } else {
                context.skippedAccounts.remove(event.uid)
                val account = AccountState(
                    id = ids.nextUUID(),
                    uid = event.uid,
                    name = name(context, event.name, "Account ${event.uid}"),
                    currency = currency,
                    balance = event.money,
                    initialBalance = event.money,
                )
                context.accounts[event.uid] = account
                context.createdAccounts.add(account)
            }
        }
    }

    override fun updateAccount(context: ChronicleSnapshotContext, event: UpdatedAccount) {
        val account = liveAccount(context, event.uid, "account update")
        if (account != null) {
            event.name?.let { account.name = name(context, it, account.name) }
            event.currency?.let { code ->
                val currency = resolveCurrency(context, code)
                when {
                    currency == null -> count(context, Code.SOURCE_ANOMALY, "currency change to an unknown code ignored")
                    currency != account.currency -> {
                        if (account.hasOperations) {
                            count(context, Code.SOURCE_ANOMALY, "currency changed on an account with operations")
                        }
                        account.currency = currency
                    }
                }
            }
            event.money
                ?.let { assignBalance(context, event.uid, it) }
        }
    }

    // Decision 7: the account's operations disappear with it, its transfer legs are orphaned for
    // balance purposes but keep naming it, and it survives archived so those legs stay valid in Core.
    override fun deleteAccount(context: ChronicleSnapshotContext, event: DeletedAccount) {
        val account = if (context.skippedAccounts.remove(event.uid)) {
            null
        } else {
            liveAccount(context, event.uid, "account deletion")
        }
        if (account != null) {
            context.operations.values.removeAll { it.account === account }
            val iterator = context.transfers.values.iterator()
            while (iterator.hasNext()) {
                val transfer = iterator.next()

                if (transfer.source === account) transfer.sourceOrphaned = true
                if (transfer.target === account) transfer.targetOrphaned = true

                if (transfer.sourceOrphaned && transfer.targetOrphaned) {
                    iterator.remove()
                    count(context, Code.SOURCE_ANOMALY, "transfer between deleted accounts dropped")
                }
            }

            context.accounts.remove(event.uid)
            account.archived = true
        }
    }

    override fun assignBalance(context: ChronicleSnapshotContext, uid: Long, money: BigDecimal) {
        val account = liveAccount(context, uid, "balance assignment")
        if (account != null) {
            account.balance = money
            val anchor = AnchorState(account, money)
            val maxDate = context.maxBusinessDate
            if (maxDate == null) {
                context.belowFloor.add(anchor)
            } else {
                val previous = context.lastStamp
                anchor.stamp = if (previous != null && previous >= maxDate) previous.plusNanos(EPSILON_NANOS)
                else maxDate.plusNanos(EPSILON_NANOS)
                context.lastStamp = anchor.stamp
            }
            context.anchors.add(anchor)
        }
    }

    override fun emitAccounts(context: ChronicleSnapshotContext) = context.createdAccounts.map {
        SnapshotAccount(it.id, it.uid, it.name, it.currency, it.initialBalance, it.initialBalanceAt!!, it.archived)
    }

    // D2: 0 or absent is "unspecified" and takes the workspace's currency; an unknown code skips.
    private fun resolveCurrency(context: ChronicleSnapshotContext, code: Int?): String? =
        if (code == null || code == 0) {
            count(context, Code.SOURCE_ANOMALY, "unspecified currency, workspace default used")
            context.defaultCurrency
        } else {
            CURRENCIES_BY_NUMERIC_CODE[code]
        }
}
