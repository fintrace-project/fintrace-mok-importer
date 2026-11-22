package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.service.interpreter.AbstractApplier.Companion.EPSILON_NANOS
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
