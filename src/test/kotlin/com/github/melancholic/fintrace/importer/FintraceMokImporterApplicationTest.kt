package com.github.melancholic.fintrace.importer

import com.github.melancholic.fintrace.importer.domain.AddNewTransaction
import com.github.melancholic.fintrace.importer.domain.AddedNewAccount
import com.github.melancholic.fintrace.importer.domain.CategoryType
import com.github.melancholic.fintrace.importer.domain.ParsedChronicle
import com.github.melancholic.fintrace.importer.service.interpreter.ChronicleInterpreter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals

@SpringBootTest
class FintraceMokImporterApplicationTest(@Autowired private val interpreter: ChronicleInterpreter) {

    @Test
    fun `the context assembles an interpreter that folds a chronicle`() {
        val chronicle = ParsedChronicle(
            listOf(
                AddedNewAccount(1, 1, "Cash", BigDecimal("100.00"), 978, ""),
                AddNewTransaction(
                    2, LocalDateTime.of(2025, 1, 1, 12, 0), 1, CategoryType.EXPENSE, 1, null, BigDecimal("30.00"), ""
                ),
            ),
            3, emptyList(), null,
        )

        val snapshot = interpreter.interpretChronicle(chronicle, LocalDateTime.of(2026, 1, 1, 0, 0), "EUR")

        val account = snapshot.accounts.single()
        assertEquals(7, account.id.version())
        assertEquals(BigDecimal("70.00"), snapshot.anchors.single { it.accountId == account.id }.value)
    }
}
