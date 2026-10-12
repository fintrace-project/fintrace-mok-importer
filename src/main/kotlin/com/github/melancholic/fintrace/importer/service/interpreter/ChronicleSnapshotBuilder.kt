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
    private val now: LocalDateTime,
    defaultCurrency: String
) {
    private val context = ChronicleSnapshotContext(defaultCurrency, now)

    fun run(chronicle: ParsedChronicle): ChronicleSnapshot {
        val floor = chronicle.events
            .mapNotNull { businessDateOf(it) }
            .minOrNull()

        for (event in chronicle.events) {
            businessDateOf(event)?.let { date ->
                if (context.maxBusinessDate == null || date > context.maxBusinessDate) {
                    context.maxBusinessDate = date
                    context.latestDated = event
                }
            }
            applier.apply(context, event)
        }

        val floorOrNow = floor ?: now.minusNanos(EPSILON_NANOS * (context.createdAccounts.size + 2L))
        stampBelowFloor(floorOrNow)
        val finals = finalAnchors(floorOrNow)

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
            },
            renumbering = chronicle.renumbering,
        )

        checkNotInFuture(snapshot)
        assertAnchorOrdering(snapshot)
        return snapshot
    }

    private fun businessDateOf(event: MokOperation): LocalDateTime? = when (event) {
        is AddNewTransaction,
        is UpdateTransaction,
        is AddedNewTransfer,
        is UpdatedTransfer -> event.date
        else -> null
    }

    // Decision 5: every opening anchor, then every balance assignment seen before the first dated
    // event, in one block ending one epsilon below the earliest business date in the log.
    private fun stampBelowFloor(floor: LocalDateTime) {
        val block = context.createdAccounts.map { OpeningStamp(it) } + context.belowFloor.map { AssignmentStamp(it) }
        block.forEachIndexed { index, stamp ->
            val at = floor.minusNanos(EPSILON_NANOS * (block.size - index))
            when (stamp) {
                is OpeningStamp -> stamp.account.initialBalanceAt = at
                is AssignmentStamp -> stamp.anchor.stamp = at
            }
        }
    }

    private fun finalAnchors(floor: LocalDateTime): List<AnchorState> {
        val base = listOfNotNull(context.maxBusinessDate, context.lastStamp, floor.minusNanos(EPSILON_NANOS)).max()
        return context.createdAccounts.mapIndexed { index, account ->
            AnchorState(account, if (account.archived) BigDecimal.ZERO.setScale(2) else account.balance)
                .also { it.stamp = base.plusNanos(EPSILON_NANOS * (index + 1)) }
        }
    }

    // D5: Core refuses a future anchor, and inventing a date would hide a real oddity in the source.
    private fun checkNotInFuture(snapshot: ChronicleSnapshot) {
        if (snapshot.anchors.any { it.occurredAt > now }) {
            throw InvalidChronicleException(
                "The chronicle runs into the future: event uid ${context.latestDated?.num} is dated ${context.maxBusinessDate}, after $now"
            )
        }
    }

    // Decision 4: violations are invisible in the figures — a balance off by one operation — so
    // they stop the import rather than reach Core.
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
            check(
                instants.zipWithNext()
                    .all { (a, b) -> a < b }) { "Anchors of account ${account.uid} are not strictly increasing" }
            check(instants.all { it.nano % 1_000 == 0 }) { "An anchor of account ${account.uid} is not microsecond-aligned" }
            check(instants.none { it in eventInstants[account.id].orEmpty() }) {
                "An anchor of account ${account.uid} shares an instant with one of its operations"
            }
        }
    }
}
