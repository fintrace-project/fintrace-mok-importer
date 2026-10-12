package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.util.sanitizeName
import java.util.*

internal abstract class AbstractApplier {

    protected fun count(context: ChronicleSnapshotContext, code: Code, detail: String) {
        context.counts.merge(code to detail, 1L, Long::plus)
    }

    protected fun name(context: ChronicleSnapshotContext, raw: String, fallback: String): String {
        val sanitized = sanitizeName(raw)
        if (sanitized != raw) count(context, Code.SOURCE_ANOMALY, "name changed to fit Core's rules")
        return sanitized ?: sanitizeName(fallback)!!
    }

    protected fun comment(context: ChronicleSnapshotContext, note: String?): String? = when {
        note.isNullOrBlank() -> null
        note.length <= MAX_COMMENT_LENGTH -> note
        else -> {
            count(context, Code.SOURCE_ANOMALY, "comment truncated")
            note.take(MAX_COMMENT_LENGTH)
        }
    }

    protected fun liveAccount(context: ChronicleSnapshotContext, uid: Long, what: String): AccountState? =
        if (uid in context.skippedAccounts) {
            null
        } else {
            knownAccount(context, uid, what)
        }

    protected fun referencedAccount(context: ChronicleSnapshotContext, uid: Long, what: String): AccountState? =
        if (uid in context.skippedAccounts) {
            count(context, Code.UNKNOWN_CURRENCY, "$what skipped with its account")
            null
        } else {
            knownAccount(context, uid, what)
        }

    private fun knownAccount(context: ChronicleSnapshotContext, uid: Long, what: String): AccountState? {
        val account = context.accounts[uid]
        if (account == null) count(context, Code.SOURCE_ANOMALY, "$what names an unknown account")
        return account
    }

    companion object {
        const val EPSILON_NANOS = 1_000L
        const val MAX_COMMENT_LENGTH = 255
        const val EXPENSE_ROOT = 1L
        const val INCOME_ROOT = 2L

        val CURRENCIES_BY_NUMERIC_CODE: Map<Int, String> = Currency.getAvailableCurrencies()
            .filter { it.numericCode > 0 }
            .associate { it.numericCode to it.currencyCode }
    }
}