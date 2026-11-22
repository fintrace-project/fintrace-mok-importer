package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.*
import com.github.melancholic.fintrace.importer.exception.InvalidChronicleException
import com.github.melancholic.fintrace.importer.service.UUIDGenerator
import com.github.melancholic.fintrace.importer.service.interpreter.AbstractApplier.Companion.EPSILON_NANOS
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

internal class ChronicleSnapshotBuilder(
    private val applier: ChronicleBaseApplier,
    private val uuidGenerator: UUIDGenerator,
    private val importStartTime: LocalDateTime,
    defaultCurrency: String
) {
    private val context = ChronicleSnapshotContext(defaultCurrency, importStartTime)

    fun run(chronicle: ParsedChronicle): ChronicleSnapshot {
        for (event in chronicle.events) {
            markDateOf(event)?.let { minOf(it, context.businessDateCap) }?.let { date ->
                if (context.maxBusinessDate == null || date > context.maxBusinessDate) {
                    context.maxBusinessDate = date
                }
            }
            val before = if (isEdit(event)) balances() else null
            applier.apply(context, event)
            before?.let { anchorLostEdits(it) }
        }

        val floor = chronicle.events
            .mapNotNull { businessDateOf(it) }
            .minOrNull()
            ?.let { minOf(it, context.businessDateCap) } ?: context.businessDateCap

        stampBelowFloor(floor)
        updateInitTimeForAccounts(context)

        val finals = finalAnchors(floor)

        val snapshot = ChronicleSnapshot(
            accounts = applier.accountApplier.emitAccounts(context),
            categories = applier.categoryApplier.emitCategories(context),
            operations = applier.operationApplier.emitOperations(context),
            transfers = applier.transferApplier.emitTransfers(context),
            anchors = (context.anchors + finals)
                .map { SnapshotAnchor(uuidGenerator.nextUUID(), it.account.id, it.stamp!!, it.value) }
                .sortedWith(compareBy({ it.occurredAt }, { it.accountId })),
            diagnostics = chronicle.diagnostics + context.counts.map { (key, count) ->
                Diagnostic(Diagnostic.Severity.WARNING, key.first, count, key.second)
            } + gatedDrifts(),
            renumbering = chronicle.renumbering,
        )

        checkNotInFuture(snapshot)
        assertAnchorOrdering(snapshot)
        return snapshot
    }

    private fun updateInitTimeForAccounts(context: ChronicleSnapshotContext) {
        context.createdAccounts
            .filter { it.createdAtMark != null }
            .forEach { acc ->
                val mark = requireNotNull(acc.createdAtMark)

                val minOpDate = context.operations.values.filter { it.account === acc }
                    .minOfOrNull { it.date } ?: mark
                val minTransferDate = context.transfers.values
                    .filter { (it.source === acc && !it.sourceOrphaned) || (it.target === acc && !it.targetOrphaned) }
                    .minOfOrNull { it.date } ?: mark

                acc.initialBalanceAt = minOf(minOf(minOpDate, minTransferDate), mark).minusNanos(EPSILON_NANOS)
            }
    }

    private fun isEdit(event: MokOperation) = event is UpdateTransaction || event is DeleteTransaction ||
            event is DeletedCategory || event is UpdatedTransfer || event is DeletedTransfer

    // An edit moves the tracked balance by one amount and the computed one by another when it touches an entry
    // behind the latest anchor, or is gated; the anchor carries only that edit's own difference.
    private fun anchorLostEdits(before: Map<AccountState, Balances>) {
        balances().forEach { (account, after) ->
            val prior = before[account] ?: return@forEach
            val lost = (after.tracked - prior.tracked) - (after.computed - prior.computed)
            if (lost.signum() != 0) context.addAnchorAtMark(AnchorState(account, after.computed + lost))
        }
    }

    private class Balances(val computed: BigDecimal, val tracked: BigDecimal)

    // Entries dated past the mark (a live future entry) would follow any new anchor, so they stay out of both.
    private fun balances(): Map<AccountState, Balances> {
        val mark = context.maxBusinessDate
        val computed = HashMap<AccountState, BigDecimal>()
        val ahead = HashMap<AccountState, BigDecimal>()
        context.accounts.values
            .forEach {
                computed[it] = context.latestAnchors[it]?.value ?: it.initialBalance
                ahead[it] = BigDecimal.ZERO
            }

        fun include(account: AccountState, date: LocalDateTime, amount: BigDecimal) {
            val sum = computed[account] ?: return
            val anchoredAt = context.latestAnchors[account]?.stamp
            when {
                mark != null && date > mark -> ahead[account] = ahead.getValue(account) + amount
                anchoredAt == null || date > anchoredAt -> computed[account] = sum + amount
            }
        }

        context.operations.values
            .forEach { include(it.account, it.date, it.signed()) }

        context.transfers.values
            .forEach {
                if (!it.sourceOrphaned) include(it.source, it.date, it.money.negate())
                if (!it.targetOrphaned) include(it.target, it.date, it.targetAmount())
            }

        return computed.mapValues { (account, sum) -> Balances(sum, account.balance - ahead.getValue(account)) }
    }

    private fun markDateOf(event: MokOperation): LocalDateTime? = when (event) {
        is AddNewTransaction,
        is AddedNewTransfer -> event.date
        else -> null
    }

    private fun businessDateOf(event: MokOperation): LocalDateTime? = when (event) {
        is AddNewTransaction,
        is UpdateTransaction,
        is AddedNewTransfer,
        is UpdatedTransfer -> event.date

        else -> null
    }

    private fun stampBelowFloor(floor: LocalDateTime) {
        val block = context.createdAccounts
            .filter { it.createdAtMark == null }
            .map { OpeningStamp(it) } + context.belowFloor.map { AssignmentStamp(it) }

        block.forEachIndexed { index, stamp ->
            val at = floor.minusNanos(EPSILON_NANOS * (block.size - index))
            when (stamp) {
                is OpeningStamp -> stamp.account.initialBalanceAt = at
                is AssignmentStamp -> stamp.anchor.stamp = at
            }
        }
    }

    private fun finalAnchors(floor: LocalDateTime): List<AnchorState> {
        val entries = context.operations.values.map { it.date } + context.transfers.values.map { it.date }
        val latestEntry = entries.maxOrNull()?.let { minOf(it, context.businessDateCap) }
        val base = listOfNotNull(context.maxBusinessDate, context.lastStamp, latestEntry, floor.minusNanos(EPSILON_NANOS)).max()
        return context.createdAccounts
            .filterNot { it.archived }
            .mapIndexed { index, account ->
                AnchorState(account, account.balance)
                    .also { it.stamp = base.plusNanos(EPSILON_NANOS * (index + 1)) }
            }
    }

    private fun gatedDrifts() = context.createdAccounts
        .filter { !it.archived && it.gatedDrift.signum() != 0 }
        .map {
            val drift = it.gatedDrift.toPlainString().let { s -> if (it.gatedDrift.signum() > 0) "+$s" else s }
            Diagnostic(
                Diagnostic.Severity.WARNING,
                Diagnostic.Code.SOURCE_ANOMALY,
                it.gatedEdits,
                "account '${it.name}': balance-gated edits leave its operations $drift off its balance",
            )
        }

    private fun checkNotInFuture(snapshot: ChronicleSnapshot) {
        val future = (snapshot.operations + snapshot.transfers).firstOrNull { it.occurredAt > importStartTime }
        if (future != null) {
            throw InvalidChronicleException(
                "The chronicle runs into the future: event uid ${future.uid} is dated ${future.occurredAt}, after $importStartTime"
            )
        }
    }

    private fun assertAnchorOrdering(snapshot: ChronicleSnapshot) {
        val anchorsByAccount = snapshot.anchors.groupBy { it.accountId }
        val eventInstants = HashMap<UUID, MutableSet<LocalDateTime>>()
        snapshot.operations.forEach { eventInstants.getOrPut(it.accountId) { HashSet() }.add(it.occurredAt) }
        snapshot.transfers.forEach { transfer ->
            listOf(transfer.source, transfer.target).forEach {
                eventInstants.getOrPut(it.accountId) { HashSet() }.add(transfer.occurredAt)
            }
        }
        for (account in snapshot.accounts) {
            val instants =
                listOf(account.initialBalanceAt) + anchorsByAccount[account.id].orEmpty().map { it.occurredAt }
            check(instants.zipWithNext().all { (a, b) -> a < b }) {
                "Anchors of account ${account.uid} are not strictly increasing"
            }
            check(instants.all { it.nano % 1_000 == 0 }) {
                "An anchor of account ${account.uid} is not microsecond-aligned"
            }
            check(instants.none { it in eventInstants[account.id].orEmpty() }) {
                "An anchor of account ${account.uid} shares an instant with one of its operations"
            }
            check(instants.none { anchorDate -> anchorDate.isAfter(importStartTime) }) {
                "An anchor of account ${account.uid} is dated after $importStartTime"
            }
        }
    }
}
