package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.domain.*
import com.github.melancholic.fintrace.importer.domain.CategoryType.EXPENSE
import com.github.melancholic.fintrace.importer.domain.CategoryType.INCOME
import com.github.melancholic.fintrace.importer.exception.InvalidChronicleException
import com.github.melancholic.fintrace.importer.service.interpreter.AccountApplierImpl
import com.github.melancholic.fintrace.importer.service.interpreter.CategoryApplierImpl
import com.github.melancholic.fintrace.importer.service.interpreter.ChronicleBaseApplier
import com.github.melancholic.fintrace.importer.service.interpreter.ChronicleInterpreter
import com.github.melancholic.fintrace.importer.service.interpreter.OperationApplierImpl
import com.github.melancholic.fintrace.importer.service.interpreter.TransferApplierImpl
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChronicleInterpreterTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)

    private fun interpretChronicle(log: Log): ChronicleSnapshot {
        val ids = CountingIds()
        val applier = ChronicleBaseApplier(
            AccountApplierImpl(ids),
            OperationApplierImpl(ids),
            TransferApplierImpl(ids),
            CategoryApplierImpl(ids),
        )
        return ChronicleInterpreter(applier, ids).interpretChronicle(ParsedChronicle(log.events, 3, emptyList(), null), now, "EUR")
    }

    private fun ChronicleSnapshot.account(uid: Long) = accounts.single { it.uid == uid }
    private fun ChronicleSnapshot.anchorsOf(uid: Long) = anchors.filter { it.accountId == account(uid).id }
    private fun ChronicleSnapshot.finalBalance(uid: Long) = anchorsOf(uid).maxBy { it.occurredAt }.value
    private fun ChronicleSnapshot.detail(detail: String) = diagnostics.singleOrNull { it.detail == detail }?.count

    @Nested
    inner class AccountsAndOperations {

        @Test
        fun `the final anchor carries the opening balance plus every signed operation`() {
            val snapshot = interpretChronicle(Log().account(1, "100").income(1, 1, "50").expense(2, 1, "30"))

            assertEquals(money("120"), snapshot.finalBalance(1))
            assertEquals(money("100"), snapshot.account(1).initialBalance)
        }

        @Test
        fun `an amount change moves the balance only when moneyBack is set`() {
            val gated = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").updateMoney(1, "25", moneyBack = true))
            val ungated = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").updateMoney(1, "25", moneyBack = false))

            assertEquals(money("-25"), gated.finalBalance(1))
            assertEquals(money("-10"), ungated.finalBalance(1))
            assertEquals(money("25"), ungated.operations.single().amount)
        }

        @Test
        fun `an account move shifts the amount only when moneyBack2 is set`() {
            fun moved(gate: Boolean) =
                interpretChronicle(Log().account(1, "0").account(2, "0").income(1, 1, "40").moveOperation(1, to = 2, moneyBack2 = gate))

            assertEquals(money("0"), moved(true).finalBalance(1))
            assertEquals(money("40"), moved(true).finalBalance(2))
            assertEquals(money("40"), moved(false).finalBalance(1))
            assertEquals(money("0"), moved(false).finalBalance(2))
            assertEquals(moved(false).account(2).id, moved(false).operations.single().accountId)
        }

        @Test
        fun `a deleted operation is reverted only when moneyBack is set`() {
            val reverted = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").deleteOperation(1, moneyBack = true))
            val kept = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").deleteOperation(1, moneyBack = false))

            assertEquals(money("0"), reverted.finalBalance(1))
            assertEquals(money("-10"), kept.finalBalance(1))
            assertTrue(kept.operations.isEmpty())
        }

        @Test
        fun `an amount change and an account move in one update follow both gates`() {
            fun updated(gates: Boolean) = interpretChronicle(
                Log().account(1, "0").account(2, "0").expense(1, 1, "10")
                    .updateMoneyAndMove(1, "25", to = 2, moneyBack = gates, moneyBack2 = gates)
            )

            assertEquals(money("0"), updated(true).finalBalance(1))
            assertEquals(money("-25"), updated(true).finalBalance(2))
            assertEquals(money("-10"), updated(false).finalBalance(1))
            assertEquals(money("0"), updated(false).finalBalance(2))
            assertEquals(updated(false).account(2).id, updated(false).operations.single().accountId)
        }

        @Test
        fun `a date update moves the operation to its new date`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10", at = day(5)).redate(1, day(2)))

            assertEquals(day(2), snapshot.operations.single().occurredAt)
        }

        @Test
        fun `a balance assignment from zero leaves the opening balance alone`() {
            val snapshot = interpretChronicle(Log().account(1, "0").assignBalance(1, "500"))

            assertEquals(money("0"), snapshot.account(1).initialBalance)
            assertEquals(money("500"), snapshot.finalBalance(1))
        }

        @Test
        fun `a balance assignment sets the balance and becomes an anchor`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").assignBalance(1, "500").income(2, 1, "5"))

            val values = snapshot.anchorsOf(1).sortedBy { it.occurredAt }.map { it.value }
            assertEquals(listOf(money("500"), money("505")), values)
        }

        @Test
        fun `an update to an unknown operation is counted, not thrown`() {
            val snapshot = interpretChronicle(Log().account(1, "0").updateMoney(99, "5", moneyBack = true))

            assertEquals(1L, snapshot.detail("update of an unknown operation ignored"))
        }

        @Test
        fun `a money verification sets the balance like an assignment`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").verifyBalance(1, "40"))

            assertEquals(money("40"), snapshot.finalBalance(1))
        }

        @Test
        fun `a duplicate account creation keeps the first and is counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").account(1, "5"))

            assertEquals(money("0"), snapshot.account(1).initialBalance)
            assertEquals(1L, snapshot.detail("duplicate account creation ignored"))
        }

        @Test
        fun `a duplicate operation creation keeps the first and is counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").expense(1, 1, "20"))

            assertEquals(money("10"), snapshot.operations.single().amount)
            assertEquals(1L, snapshot.detail("duplicate operation creation ignored"))
        }

        @Test
        fun `a deletion of an unknown operation is counted, not thrown`() {
            val snapshot = interpretChronicle(Log().account(1, "0").deleteOperation(99, moneyBack = true))

            assertEquals(1L, snapshot.detail("deletion of an unknown operation ignored"))
        }

        @Test
        fun `an operation naming a category that never existed has none, counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10", category = 99))

            assertNull(snapshot.operations.single().categoryId)
            assertEquals(1L, snapshot.detail("operation category no longer exists"))
        }

        @Test
        fun `an event type the fold does not handle changes nothing`() {
            val snapshot = interpretChronicle(Log().account(1, "5").version())

            assertEquals(money("5"), snapshot.finalBalance(1))
        }
    }

    @Nested
    inner class CurrencyChanges {

        @Test
        fun `a currency change on an account without operations is taken silently`() {
            val snapshot = interpretChronicle(Log().account(1, "0").changeCurrency(1, 840))

            assertEquals("USD", snapshot.account(1).currency)
            assertNull(snapshot.detail("currency changed on an account with operations"))
        }

        @Test
        fun `a currency change on an account with operations is taken and counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").changeCurrency(1, 840))

            assertEquals("USD", snapshot.account(1).currency)
            assertEquals(1L, snapshot.detail("currency changed on an account with operations"))
        }

        @Test
        fun `a change to the same currency is not counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "10").changeCurrency(1, 978))

            assertEquals("EUR", snapshot.account(1).currency)
            assertNull(snapshot.detail("currency changed on an account with operations"))
        }

        @Test
        fun `a currency change to an unknown code keeps the old one, counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").changeCurrency(1, 10001))

            assertEquals("EUR", snapshot.account(1).currency)
            assertEquals(1L, snapshot.detail("currency change to an unknown code ignored"))
        }

        @Test
        fun `an update to an account skipped for its currency is ignored`() {
            val snapshot = interpretChronicle(Log().account(1, "0", currency = 10001).assignBalance(1, "5"))

            assertTrue(snapshot.accounts.isEmpty())
            assertTrue(snapshot.anchors.isEmpty())
        }

        @Test
        fun `deleting an account skipped for its currency frees its uid`() {
            val snapshot = interpretChronicle(Log().account(1, "0", currency = 10001).deleteAccount(1).assignBalance(1, "5"))

            assertTrue(snapshot.accounts.isEmpty())
            assertEquals(1L, snapshot.detail("account update names an unknown account"))
        }
    }

    @Nested
    inner class AccountDeletion {

        @Test
        fun `a deleted account is archived with its id, at zero, and its operations disappear`() {
            val snapshot = interpretChronicle(Log().account(1, "100").expense(1, 1, "10").deleteAccount(1))

            val account = snapshot.account(1)
            assertTrue(account.archived)
            assertTrue(snapshot.operations.isEmpty())
            assertEquals(money("0"), snapshot.finalBalance(1))
        }

        @Test
        fun `a uid reused after deletion is a new account`() {
            val snapshot = interpretChronicle(Log().account(1, "0").deleteAccount(1).account(1, "7"))

            val (old, new) = snapshot.accounts
            assertNotEquals(old.id, new.id)
            assertTrue(old.archived)
            assertEquals(money("7"), new.initialBalance)
        }

        @Test
        fun `a transfer leg keeps naming the deleted account while its balance stops moving`() {
            val snapshot = interpretChronicle(
                Log().account(1, "100").account(2, "0")
                    .transfer(1, from = 1, to = 2, money = "30")
                    .deleteAccount(2)
                    .updateTransferMoney(1, "50")
            )

            val transfer = snapshot.transfers.single()
            assertEquals(snapshot.account(2).id, transfer.target.accountId)
            assertEquals(money("50"), snapshot.finalBalance(1))
            assertEquals(money("0"), snapshot.finalBalance(2))
        }

        @Test
        fun `deleting a transfer with an orphaned leg reverts only the surviving side`() {
            val snapshot = interpretChronicle(
                Log().account(1, "100").account(2, "0")
                    .transfer(1, from = 1, to = 2, money = "30")
                    .deleteAccount(2)
                    .deleteTransfer(1)
            )

            assertTrue(snapshot.transfers.isEmpty())
            assertEquals(money("100"), snapshot.finalBalance(1))
            assertEquals(money("0"), snapshot.finalBalance(2))
        }

        @Test
        fun `a transfer whose sides are deleted in separate events is dropped, in either order`() {
            fun deletedInOrder(first: Long, second: Long) = interpretChronicle(
                Log().account(1, "100").account(2, "0").account(3, "0")
                    .transfer(1, from = 1, to = 2, money = "30")
                    .deleteAccount(first)
                    .deleteAccount(3)
                    .deleteAccount(second)
            )

            for (snapshot in listOf(deletedInOrder(1, 2), deletedInOrder(2, 1))) {
                assertTrue(snapshot.transfers.isEmpty())
                assertEquals(1, snapshot.detail("transfer between deleted accounts dropped"))
            }
        }
    }

    @Nested
    inner class Currencies {

        @Test
        fun `an ISO numeric code becomes its alpha code`() {
            assertEquals("USD", interpretChronicle(Log().account(1, "0", currency = 840)).account(1).currency)
        }

        @Test
        fun `an unspecified currency takes the workspace default`() {
            val snapshot = interpretChronicle(Log().account(1, "0", currency = 0))

            assertEquals("EUR", snapshot.account(1).currency)
            assertEquals(1L, snapshot.detail("unspecified currency, workspace default used"))
        }

        @Test
        fun `an unknown currency skips the account and everything naming it`() {
            val snapshot = interpretChronicle(
                Log().account(1, "0", currency = 10001).account(2, "0")
                    .expense(1, 1, "5").transfer(1, from = 2, to = 1, money = "3")
            )

            assertEquals(listOf(2L), snapshot.accounts.map { it.uid })
            assertTrue(snapshot.operations.isEmpty())
            assertTrue(snapshot.transfers.isEmpty())
            assertEquals(1L, snapshot.detail("account skipped"))
        }
    }

    @Nested
    inner class Transfers {

        @Test
        fun `a cross-currency transfer debits money and credits moneyTo`() {
            val snapshot = interpretChronicle(Log().account(1, "100").account(2, "0").transfer(1, 1, 2, "100", moneyTo = "92.40"))

            assertEquals(money("0"), snapshot.finalBalance(1))
            assertEquals(money("92.40"), snapshot.finalBalance(2))
            assertEquals(money("92.40"), snapshot.transfers.single().target.amount)
        }

        @Test
        fun `a note-only update resets moneyTo to the debited amount, as the vendor does`() {
            val snapshot = interpretChronicle(
                Log().account(1, "100").account(2, "0")
                    .transfer(1, 1, 2, "100", moneyTo = "92.40")
                    .updateTransferNote(1, "edited")
            )

            assertEquals(money("100"), snapshot.transfers.single().target.amount)
            assertEquals(money("100"), snapshot.finalBalance(2))
        }

        @Test
        fun `a date update moves the transfer to its new date`() {
            val snapshot = interpretChronicle(Log().account(1, "100").account(2, "0").transfer(1, 1, 2, "40", at = day(5)).redateTransfer(1, day(2)))

            assertEquals(day(2), snapshot.transfers.single().occurredAt)
        }

        @Test
        fun `a deleted transfer reverts both sides`() {
            val snapshot = interpretChronicle(Log().account(1, "100").account(2, "0").transfer(1, 1, 2, "40").deleteTransfer(1))

            assertEquals(money("100"), snapshot.finalBalance(1))
            assertEquals(money("0"), snapshot.finalBalance(2))
            assertTrue(snapshot.transfers.isEmpty())
        }

        @Test
        fun `an update re-points both legs and moves the balances with them`() {
            val snapshot = interpretChronicle(
                Log().account(1, "100").account(2, "0").account(3, "100").account(4, "0")
                    .transfer(1, 1, 2, "30")
                    .repointTransfer(1, from = 3, to = 4)
            )

            val transfer = snapshot.transfers.single()
            assertEquals(snapshot.account(3).id, transfer.source.accountId)
            assertEquals(snapshot.account(4).id, transfer.target.accountId)
            assertEquals(listOf(money("100"), money("0"), money("70"), money("30")), (1L..4L).map { snapshot.finalBalance(it) })
        }

        @Test
        fun `re-pointing a leg away from a deleted account makes it move balances again`() {
            val snapshot = interpretChronicle(
                Log().account(1, "100").account(2, "0").account(3, "0")
                    .transfer(1, 1, 2, "30")
                    .deleteAccount(2)
                    .repointTransfer(1, to = 3)
                    .updateTransferMoney(1, "50")
            )

            assertEquals(snapshot.account(3).id, snapshot.transfers.single().target.accountId)
            assertEquals(money("50"), snapshot.finalBalance(1))
            assertEquals(money("50"), snapshot.finalBalance(3))
        }

        @Test
        fun `a duplicate transfer creation keeps the first and is counted`() {
            val snapshot = interpretChronicle(Log().account(1, "100").account(2, "0").transfer(1, 1, 2, "30").transfer(1, 1, 2, "60"))

            assertEquals(money("30"), snapshot.transfers.single().source.amount)
            assertEquals(1L, snapshot.detail("duplicate transfer creation ignored"))
        }

        @Test
        fun `an update or deletion of an unknown transfer is counted, not thrown`() {
            val snapshot = interpretChronicle(Log().account(1, "0").updateTransferMoney(99, "5").deleteTransfer(98))

            assertEquals(1L, snapshot.detail("update of an unknown transfer ignored"))
            assertEquals(1L, snapshot.detail("deletion of an unknown transfer ignored"))
        }
    }

    @Nested
    inner class Categories {

        @Test
        fun `a group hangs under its kind's root and a category under its group`() {
            val snapshot = interpretChronicle(Log().group(3, "Home", EXPENSE).category(7, "Rent", EXPENSE, group = 3).category(8, "Salary", INCOME, group = 2))

            val group = snapshot.categories.single { it.isGroup }
            assertNull(group.parentId)
            assertEquals(group.id, snapshot.categories.single { it.uid == 7L && !it.isGroup }.parentId)
            assertNull(snapshot.categories.single { it.uid == 8L }.parentId)
        }

        @Test
        fun `a move sets the final parent and a rename the final name`() {
            val snapshot = interpretChronicle(
                Log().group(3, "Home", EXPENSE).group(4, "Car", EXPENSE)
                    .category(7, "Rent", EXPENSE, group = 3).move(7, to = 4).renameCategory(7, "Parking")
            )

            val category = snapshot.categories.single { !it.isGroup }
            assertEquals(snapshot.categories.single { it.uid == 4L && it.isGroup }.id, category.parentId)
            assertEquals("Parking", category.name)
        }

        @Test
        fun `a deleted group leaves its categories under the root, counted`() {
            val snapshot = interpretChronicle(Log().group(3, "Home", EXPENSE).category(7, "Rent", EXPENSE, group = 3).deleteGroup(3))

            assertNull(snapshot.categories.single().parentId)
            assertEquals(1L, snapshot.detail("category group no longer exists"))
        }

        @Test
        fun `a deleted category takes its operations, reverting them only with moneyBack`() {
            fun deleted(gate: Boolean) = interpretChronicle(
                Log().category(7, "Rent", EXPENSE, group = 1).account(1, "0")
                    .expense(1, 1, "10", category = 7).deleteCategory(7, moneyBack = gate)
            )

            assertTrue(deleted(true).operations.isEmpty())
            assertEquals(money("0"), deleted(true).finalBalance(1))
            assertEquals(money("-10"), deleted(false).finalBalance(1))
        }

        @Test
        fun `an operation keeps its category, or none when the category is of the other kind`() {
            val snapshot = interpretChronicle(
                Log().category(7, "Rent", EXPENSE, group = 1).category(8, "Salary", INCOME, group = 2).account(1, "0")
                    .expense(1, 1, "10", category = 7).expense(2, 1, "5", category = 8)
            )

            val (filed, mismatched) = snapshot.operations
            assertEquals(snapshot.categories.single { it.uid == 7L }.id, filed.categoryId)
            assertNull(mismatched.categoryId)
        }

        @Test
        fun `a group rename sets the group's final name`() {
            val snapshot = interpretChronicle(Log().group(3, "Home", EXPENSE).renameGroup(3, "House"))

            assertEquals("House", snapshot.categories.single().name)
        }

        @Test
        fun `a group and a category share no uid space for renames`() {
            val snapshot = interpretChronicle(Log().group(3, "Home", EXPENSE).category(3, "Rent", EXPENSE, group = 3).renameGroup(3, "House"))

            assertEquals("House", snapshot.categories.single { it.isGroup }.name)
            assertEquals("Rent", snapshot.categories.single { !it.isGroup }.name)
        }

        @Test
        fun `edits of unknown categories are counted, not thrown`() {
            val snapshot = interpretChronicle(Log().renameCategory(99, "x").renameGroup(98, "y").deleteCategory(97, moneyBack = false).move(96, to = 1))

            assertEquals(2L, snapshot.detail("rename of an unknown category ignored"))
            assertEquals(1L, snapshot.detail("deletion of an unknown category ignored"))
            assertEquals(1L, snapshot.detail("move of an unknown category ignored"))
        }

        @Test
        fun `a duplicate category creation keeps the first and is counted`() {
            val snapshot = interpretChronicle(Log().category(7, "Rent", EXPENSE, group = 1).category(7, "Food", EXPENSE, group = 1))

            assertEquals("Rent", snapshot.categories.single().name)
            assertEquals(1L, snapshot.detail("duplicate category creation ignored"))
        }

        @Test
        fun `a category under the other kind's root or group goes to its own root, counted`() {
            val snapshot = interpretChronicle(
                Log().group(3, "Home", EXPENSE)
                    .category(7, "Salary", INCOME, group = 1)
                    .category(8, "Bonus", INCOME, group = 3)
            )

            assertTrue(snapshot.categories.filter { !it.isGroup }.all { it.parentId == null })
            assertEquals(1L, snapshot.detail("category under the other kind's root"))
            assertEquals(1L, snapshot.detail("category under a group of the other kind"))
        }

        @Test
        fun `a move cycle among groups is cut where it closes, counted`() {
            val snapshot = interpretChronicle(Log().group(3, "Home", EXPENSE).group(4, "Car", EXPENSE).moveGroup(3, to = 4).moveGroup(4, to = 3))

            assertEquals(1, snapshot.categories.count { it.parentId == null })
            assertEquals(1L, snapshot.detail("category cycle cut"))
        }
    }

    @Nested
    inner class Dating {

        @Test
        fun `every opening anchor precedes the earliest business date in the log`() {
            val snapshot = interpretChronicle(Log().account(1, "0").income(1, 1, "5", at = day(10)).account(2, "0").income(2, 2, "5", at = day(3)))

            assertTrue(snapshot.accounts.all { it.initialBalanceAt < day(3) })
            assertNotEquals(snapshot.account(1).initialBalanceAt, snapshot.account(2).initialBalanceAt)
        }

        @Test
        fun `an assignment after a back-dated operation still follows the account's previous anchor`() {
            val snapshot = interpretChronicle(
                Log().account(1, "0").income(1, 1, "5", at = day(10)).assignBalance(1, "50")
                    .income(2, 1, "5", at = day(2)).assignBalance(1, "80")
            )

            val assignments = snapshot.anchorsOf(1).sortedBy { it.occurredAt }.map { it.value }
            assertEquals(listOf(money("50"), money("80"), money("80")), assignments)
            assertTrue(snapshot.anchorsOf(1).all { it.occurredAt > day(10) })
        }

        @Test
        fun `an assignment before every dated event sits below the floor, after the opening anchor`() {
            val snapshot = interpretChronicle(Log().account(1, "0").assignBalance(1, "20").income(1, 1, "5", at = day(5)))

            val assignment = snapshot.anchorsOf(1).single { it.value == money("20") }
            assertTrue(snapshot.account(1).initialBalanceAt < assignment.occurredAt)
            assertTrue(assignment.occurredAt < day(5))
            assertEquals(money("25"), snapshot.finalBalance(1))
        }

        @Test
        fun `a log with no dated events at all still dates everything in the past`() {
            val snapshot = interpretChronicle(Log().account(1, "10").account(2, "0").assignBalance(1, "15"))

            assertTrue(snapshot.anchors.all { it.occurredAt <= now })
            assertTrue(snapshot.accounts.all { it.initialBalanceAt < now })
            assertEquals(money("15"), snapshot.finalBalance(1))
        }

        @Test
        fun `final anchors follow every event`() {
            val snapshot = interpretChronicle(Log().account(1, "0").account(2, "0").income(1, 1, "5", at = day(20)).transfer(1, 1, 2, "1", at = day(21)))

            val latestEvent = day(21)
            assertTrue(snapshot.accounts.all { account -> snapshot.anchorsOf(account.uid).maxOf { it.occurredAt } > latestEvent })
        }

        @Test
        fun `a chronicle running into the future is refused`() {
            assertFailsWith<InvalidChronicleException> {
                interpretChronicle(Log().account(1, "0").income(1, 1, "5", at = now.plusDays(3)))
            }
        }

        @Test
        fun `every account's anchors are strictly increasing and microsecond-aligned`() {
            val snapshot = interpretChronicle(
                Log().account(1, "0").account(2, "0").assignBalance(1, "1").assignBalance(2, "2")
                    .income(1, 1, "5", at = day(8)).assignBalance(1, "3").assignBalance(1, "4")
                    .income(2, 2, "5", at = day(4)).assignBalance(2, "6")
            )

            for (account in snapshot.accounts) {
                val instants = listOf(account.initialBalanceAt) + snapshot.anchorsOf(account.uid).map { it.occurredAt }.sorted()
                assertTrue(instants.zipWithNext().all { (a, b) -> a < b })
                assertTrue(instants.all { it.nano % 1_000 == 0 })
            }
        }
    }

    @Nested
    inner class CoreLimits {

        @Test
        fun `a zero-amount operation is dropped and counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "0"))

            assertTrue(snapshot.operations.isEmpty())
            assertEquals(1L, snapshot.detail("operation without a positive amount dropped"))
        }

        @Test
        fun `a transfer within one account is dropped and counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").transfer(1, 1, 1, "5"))

            assertTrue(snapshot.transfers.isEmpty())
            assertEquals(1L, snapshot.detail("transfer within one account dropped"))
        }

        @Test
        fun `a zero-amount transfer is dropped and counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").account(2, "0").transfer(1, 1, 2, "0"))

            assertTrue(snapshot.transfers.isEmpty())
            assertEquals(1L, snapshot.detail("transfer without a positive amount dropped"))
        }

        @Test
        fun `a long comment is truncated to 255 and counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0").expense(1, 1, "1", note = "x".repeat(300)))

            assertEquals(255, snapshot.operations.single().comment!!.length)
            assertEquals(1L, snapshot.detail("comment truncated"))
        }

        @Test
        fun `a name outside Core's rules is sanitised and counted`() {
            val snapshot = interpretChronicle(Log().account(1, "0", name = "Visa/Mastercard 💳"))

            assertEquals("Visa Mastercard", snapshot.account(1).name)
            assertEquals(1L, snapshot.detail("name changed to fit Core's rules"))
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private fun day(n: Int): LocalDateTime = LocalDateTime.of(2025, 1, n, 12, 0)

    private fun money(value: String) = BigDecimal(value).setScale(2)

    private class CountingIds : UUIDGenerator {
        private var next = 1L
        override fun nextUUID(): UUID = UUID(0x0199000000007000L, next++)
    }

    private inner class Log {
        val events = ArrayList<MokOperation>()
        private var num = 0L
        private fun add(event: (Long) -> MokOperation) = apply { events.add(event(++num)) }

        fun account(uid: Long, money: String, currency: Int? = 978, name: String = "Account $uid") =
            add { AddedNewAccount(it, uid, name, money(money), currency, "") }

        fun assignBalance(uid: Long, money: String) =
            add { UpdatedAccount(it, uid, null, money(money), null, null) }

        fun changeCurrency(uid: Long, currency: Int) =
            add { UpdatedAccount(it, uid, null, null, currency, null) }

        fun verifyBalance(uid: Long, money: String) = add { AccountMoneyVerification(it, null, uid, money(money)) }

        fun deleteAccount(uid: Long) = add { DeletedAccount(it, uid) }

        fun version() = add { ChronicleVersion(it, 3) }

        fun income(uid: Long, account: Long, money: String, at: LocalDateTime = day(1), category: Long? = null) =
            add { AddNewTransaction(it, at, uid, INCOME, account, category, money(money), "") }

        fun expense(uid: Long, account: Long, money: String, at: LocalDateTime = day(1), category: Long? = null, note: String = "") =
            add { AddNewTransaction(it, at, uid, EXPENSE, account, category, money(money), note) }

        fun updateMoney(uid: Long, money: String, moneyBack: Boolean) =
            add { UpdateTransaction(it, null, uid, null, null, money(money), moneyBack, null, null) }

        fun moveOperation(uid: Long, to: Long, moneyBack2: Boolean) =
            add { UpdateTransaction(it, null, uid, to, null, null, null, moneyBack2, null) }

        fun updateMoneyAndMove(uid: Long, money: String, to: Long, moneyBack: Boolean, moneyBack2: Boolean) =
            add { UpdateTransaction(it, null, uid, to, null, money(money), moneyBack, moneyBack2, null) }

        fun redate(uid: Long, at: LocalDateTime) =
            add { UpdateTransaction(it, at, uid, null, null, null, null, null, null) }

        fun deleteOperation(uid: Long, moneyBack: Boolean) = add { DeleteTransaction(it, null, uid, moneyBack) }

        fun transfer(uid: Long, from: Long, to: Long, money: String, moneyTo: String? = null, at: LocalDateTime = day(1)) =
            add { AddedNewTransfer(it, at, uid, money(money), moneyTo?.let(::money), from, to, "") }

        fun updateTransferMoney(uid: Long, money: String) =
            add { UpdatedTransfer(it, null, uid, money(money), null, null, null, null) }

        fun updateTransferNote(uid: Long, note: String) =
            add { UpdatedTransfer(it, null, uid, null, null, null, null, note) }

        fun redateTransfer(uid: Long, at: LocalDateTime) =
            add { UpdatedTransfer(it, at, uid, null, null, null, null, null) }

        fun repointTransfer(uid: Long, from: Long? = null, to: Long? = null) =
            add { UpdatedTransfer(it, null, uid, null, null, from, to, null) }

        fun deleteTransfer(uid: Long) = add { DeletedTransfer(it, null, uid) }

        fun group(uid: Long, name: String, kind: CategoryType) = add { AddedNewCategoriesGroup(it, uid, name, kind) }

        fun renameGroup(uid: Long, name: String) = add { UpdatedCategoriesGroup(it, uid, name) }

        fun moveGroup(uid: Long, to: Long) = add { MovedItem(it, uid, true, to) }

        fun deleteGroup(uid: Long) = add { DeletedCategoriesGroup(it, uid) }

        fun category(uid: Long, name: String, kind: CategoryType, group: Long) =
            add { AddedNewCategory(it, uid, name, kind, group) }

        fun renameCategory(uid: Long, name: String) = add { UpdatedCategory(it, uid, name) }

        fun deleteCategory(uid: Long, moneyBack: Boolean) = add { DeletedCategory(it, uid, moneyBack) }

        fun move(uid: Long, to: Long) = add { MovedItem(it, uid, false, to) }
    }
}
