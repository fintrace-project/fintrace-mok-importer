package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.service.interpreter.AbstractApplier.Companion.EPSILON_NANOS
import java.math.BigDecimal
import java.time.LocalDateTime

internal class ChronicleSnapshotContext(val defaultCurrency: String, importStartTime: LocalDateTime) {
    val businessDateCap: LocalDateTime = importStartTime.minusSeconds(1)
    val accounts = LinkedHashMap<Long, AccountState>()
    val createdAccounts = ArrayList<AccountState>()
    val skippedAccounts = HashSet<Long>()
    val operations = LinkedHashMap<Long, OperationState>()
    val transfers = LinkedHashMap<Long, TransferState>()
    val groups = LinkedHashMap<Long, CategoryState>()
    val categories = LinkedHashMap<Long, CategoryState>()

    val anchors = ArrayList<AnchorState>()
    val latestAnchors = HashMap<AccountState, AnchorState>()
    val belowFloor = ArrayList<AnchorState>()
    var maxBusinessDate: LocalDateTime? = null
    var lastStamp: LocalDateTime? = null

    val counts = LinkedHashMap<Pair<Code, String>, Long>()

    fun aheadOfMark(account: AccountState): BigDecimal {
        val mark = maxBusinessDate ?: return BigDecimal.ZERO
        var sum = BigDecimal.ZERO
        operations.values
            .filter { it.account === account && it.date > mark }
            .forEach { sum += it.signed() }
        transfers.values
            .filter { it.date > mark }
            .forEach {
                if (!it.sourceOrphaned && it.source === account) sum -= it.money
                if (!it.targetOrphaned && it.target === account) sum += it.targetAmount()
            }
        return sum
    }

    fun addAnchorAtMark(anchor: AnchorState) {
        val maxDate = maxBusinessDate
        if (maxDate == null) {
            belowFloor.add(anchor)
        } else {
            val previous = lastStamp
            anchor.stamp = if (previous != null && previous >= maxDate) {
                previous.plusNanos(EPSILON_NANOS)
            } else {
                maxDate.plusNanos(EPSILON_NANOS)
            }
            lastStamp = anchor.stamp
        }
        anchors.add(anchor)
        latestAnchors[anchor.account] = anchor
    }
}
