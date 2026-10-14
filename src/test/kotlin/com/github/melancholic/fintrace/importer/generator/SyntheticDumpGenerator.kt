package com.github.melancholic.fintrace.importer.generator

import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random

/**
 * Writes an optimistic MOK chronicle — one that imports with zero diagnostics — and tracks every
 * account's balance by the vendor's replay rules (`DDChronicleToDbConverter`) as it goes. The figures
 * check the pipeline, not our reading of the format: see docs/plans/M2-synthetic-dump.md.
 */
class SyntheticDumpGenerator(private val size: SyntheticDumpSize) {

    private val random = Random(size.seed)
    private val rows = ArrayList<ChronicleRow>()

    private val accounts = LinkedHashMap<Long, Account>()
    private val createdAccounts = ArrayList<Account>()
    private val groups = LinkedHashMap<Long, Group>()
    private val categories = LinkedHashMap<Long, Category>()
    private val operations = LinkedHashMap<Long, Operation>()
    private val transfers = LinkedHashMap<Long, Transfer>()

    private var nextGroupUid = 3L
    private var nextCategoryUid = 1L
    private var nextOperationUid = 1L
    private var nextTransferUid = 1L
    private lateinit var clock: LocalDateTime

    fun generate(): SyntheticDump {
        groups[EXPENSE_ROOT] = Group(EXPENSE_ROOT, Kind.EXPENSE, "expenseRoot")
        groups[INCOME_ROOT] = Group(INCOME_ROOT, Kind.INCOME, "incomeRoot")
        emit(1, mapOf("version" to 3))
        setUp()

        var day = size.start
        var index = 0
        while (!day.isAfter(size.end)) {
            clock = day.atTime(8, 0)
            scripted(index, day)
            repeat(size.actionsPerDay.random()) { randomAction() }
            day = day.plusDays(1)
            index++
        }
        return SyntheticDump(size, rows, expected())
    }

    private fun setUp() {
        createAccount("Cash", EUR, money(150))
        createAccount("Card", EUR, money(1200))
        createAccount("Savings", USD, money(3000))
        createAccount("Dinar wallet", RSD, money(15000))

        val food = createGroup("Food", Kind.EXPENSE)
        val home = createGroup("Home", Kind.EXPENSE)
        val side = createGroup("Side income", Kind.INCOME)

        createCategory("Transport", Kind.EXPENSE, EXPENSE_ROOT)
        createCategory("Health", Kind.EXPENSE, EXPENSE_ROOT)
        createCategory("Old stuff", Kind.EXPENSE, EXPENSE_ROOT)
        createCategory("Groceries", Kind.EXPENSE, food)
        createCategory("Cafes", Kind.EXPENSE, food)
        createCategory("Rent", Kind.EXPENSE, home)
        createCategory("Utilities", Kind.EXPENSE, home)
        createCategory("Salary", Kind.INCOME, INCOME_ROOT)
        createCategory("Gifts", Kind.INCOME, INCOME_ROOT)
        createCategory("Freelance", Kind.INCOME, side)
    }

    // The coverage floor: every everyday action at least once, whatever the proportions leave out.
    private fun scripted(index: Int, day: LocalDate) {
        if (day.dayOfMonth == 1) createOperation(Kind.INCOME, account("Card"), category("Salary"), money(2500))
        when (index) {
            3 -> createOperation(Kind.EXPENSE, account("Cash"), category("Groceries"), BigDecimal("12.50"))
            10 -> updateOperationMoney(randomOperation(), gate = 1)
            11 -> updateOperationMoney(randomOperation(), gate = 0)
            12 -> moveOperation(randomOperation(), gate = 1)
            13 -> moveOperation(randomOperation(), gate = 0)
            14 -> redateOperation(randomOperation(), backwards = true)
            15 -> redateOperation(randomOperation(), backwards = false)
            16 -> recategorise(randomOperation())
            20 -> deleteOperation(randomOperation(), gate = 1)
            21 -> deleteOperation(randomOperation(), gate = 0)
            25 -> createTransfer(account("Card"), account("Cash"))
            26 -> createTransfer(account("Card"), account("Savings"))
            27 -> updateTransferMoney(transfers.values.last { it.moneyTo != null })
            28 -> updateTransferNote(transfers.values.last { it.moneyTo != null })
            30 -> deleteTransfer(createTransfer(account("Cash"), account("Card")))
            40 -> {
                val cash = account("Cash")
                createOperation(Kind.EXPENSE, cash, category("Health"), money(35), date = clock.minusDays(20))
                assignBalance(cash)
            }
            60 -> createAccount("Travel card", EUR, money(0))
            61 -> createTransfer(account("Card"), account("Travel card"), money(400))
            62 -> createTransfer(account("Travel card"), account("Cash"), money(100))
            63 -> createOperation(Kind.EXPENSE, account("Travel card"), category("Transport"), money(60))
            70 -> deleteAccount(account("Travel card"))
            71 -> account("Card").let { card -> repointSource(transfers.values.last { it.from == ORPHANED && it.to != card.uid }, card) }
            72 -> createAccount("New card", EUR, money(0))
            73 -> createOperation(Kind.EXPENSE, account("New card"), category("Cafes"), BigDecimal("8.90"))
            80 -> createCategory("Books", Kind.EXPENSE, createGroup("Hobbies", Kind.EXPENSE))
            90 -> renameGroup(group("Food"), "Food and drink")
            95 -> renameCategory(category("Cafes"), "Cafes and bars")
            100 -> moveCategory(category("Utilities"), EXPENSE_ROOT)
            105 -> createOperation(Kind.EXPENSE, account("Cash"), category("Old stuff"), money(15))
            110 -> deleteCategory(category("Old stuff"))
        }
    }

    private fun randomAction() {
        val roll = random.nextInt(1000)
        when {
            roll < 520 || operations.isEmpty() -> createOperation()
            roll < 900 -> updateOperation(randomOperation())
            roll < 910 -> deleteOperation(randomOperation(), gate = random.nextInt(2))
            roll < 968 -> randomTransfer()
            roll < 973 && transfers.isNotEmpty() -> updateTransferMoney(transfers.values.random())
            roll < 990 -> assignBalance(accounts.values.random())
            else -> createOperation()
        }
    }

    private fun createAccount(name: String, currency: Currency, opening: BigDecimal): Account {
        val account = Account((accounts.keys.maxOrNull() ?: 0) + 1, name, currency, opening)
        accounts[account.uid] = account
        createdAccounts.add(account)
        emit(20, mapOf("uid" to account.uid, "name" to name, "money" to json(opening), "currency" to currency.numeric))
        return account
    }

    private fun deleteAccount(account: Account) {
        accounts.remove(account.uid)
        account.archived = true
        operations.values.removeIf { it.account == account.uid }
        transfers.values.forEach {
            if (it.from == account.uid) it.from = ORPHANED
            if (it.to == account.uid) it.to = ORPHANED
        }
        emit(22, mapOf("uid" to account.uid))
    }

    private fun assignBalance(account: Account) {
        val correction = if (random.nextBoolean()) BigDecimal.ZERO else cents(random.nextLong(-2000, 2000))
        account.balance = account.balance.add(correction)
        emit(21, mapOf("uid" to account.uid, "money" to json(account.balance)))
    }

    private fun createGroup(name: String, kind: Kind): Long {
        val group = Group(nextGroupUid++, kind, name)
        groups[group.uid] = group
        groups.getValue(kind.root).items.add(Item(group.uid, isGroup = true))
        emit(30, mapOf("uid" to group.uid, "name" to name, "opType" to kind.code))
        return group.uid
    }

    private fun renameGroup(group: Group, name: String) {
        group.name = name
        emit(31, mapOf("uid" to group.uid, "name" to name))
    }

    private fun createCategory(name: String, kind: Kind, group: Long) {
        val category = Category(nextCategoryUid++, kind, name, group)
        categories[category.uid] = category
        groups.getValue(group).items.add(Item(category.uid, isGroup = false))
        emit(40, mapOf("uid" to category.uid, "name" to name, "opType" to kind.code, "icon" to "otherItem.png", "groupUid" to group))
    }

    private fun renameCategory(category: Category, name: String) {
        category.name = name
        emit(41, mapOf("uid" to category.uid, "name" to name))
    }

    private fun moveCategory(category: Category, target: Long) {
        val source = groups.getValue(category.group)
        val fromIndex = source.items.indexOfFirst { !it.isGroup && it.uid == category.uid }
        val item = source.items.removeAt(fromIndex)
        val destination = groups.getValue(target)
        destination.items.add(0, item)
        category.group = target
        emit(45, mapOf(
            "uid" to category.uid, "isGroup" to 0, "groupUid" to source.uid,
            "fromIndex" to fromIndex, "newGroupUid" to target, "toIndex" to 0,
        ))
    }

    private fun deleteCategory(category: Category) {
        categories.remove(category.uid)
        groups.getValue(category.group).items.removeIf { !it.isGroup && it.uid == category.uid }
        operations.values.filter { it.category == category.uid }.forEach {
            accounts.getValue(it.account).apply { balance = balance.subtract(category.kind.signed(it.money)) }
            operations.remove(it.uid)
        }
        emit(42, mapOf("uid" to category.uid, "moneyBack" to 1))
    }

    private fun createOperation(
        kind: Kind = if (random.nextInt(100) < 12) Kind.INCOME else Kind.EXPENSE,
        account: Account = accounts.values.random(),
        category: Category = categories.values.filter { it.kind == kind }.random(),
        money: BigDecimal = amount(kind, account.currency),
        date: LocalDateTime = if (random.nextInt(20) == 0) backdated() else tick(),
    ) {
        val operation = Operation(nextOperationUid++, kind, account.uid, category.uid, money, date)
        operations[operation.uid] = operation
        account.balance = account.balance.add(kind.signed(money))
        emit(10, buildMap {
            put("uid", operation.uid)
            put("catUid", category.uid)
            put("accUid", account.uid)
            put("money", json(money))
            put("opType", kind.code)
            put("date", format(date))
            if (random.nextInt(4) == 0) put("note", NOTES.random())
        })
    }

    private fun updateOperation(operation: Operation) {
        val roll = random.nextInt(100)
        when {
            roll < 30 -> redateOperation(operation, backwards = random.nextBoolean())
            roll < 55 -> emit(11, mapOf("uid" to operation.uid, "note" to NOTES.random()))
            roll < 75 -> recategorise(operation)
            roll < 85 -> updateOperationMoney(operation, gate = 1)
            roll < 90 -> updateOperationMoney(operation, gate = 0)
            roll < 97 -> moveOperation(operation, gate = 1)
            else -> moveOperation(operation, gate = 0)
        }
    }

    private fun updateOperationMoney(operation: Operation, gate: Int) {
        val account = accounts.getValue(operation.account)
        val money = generateSequence { amount(operation.kind, account.currency) }.first { it != operation.money }
        if (gate == 1) account.balance = account.balance.add(operation.kind.signed(money.subtract(operation.money)))
        operation.money = money
        emit(11, mapOf("uid" to operation.uid, "money" to json(money), "moneyBack" to gate))
    }

    private fun moveOperation(operation: Operation, gate: Int) {
        val target = accounts.values.filter { it.uid != operation.account }.random()
        if (gate == 1) {
            accounts.getValue(operation.account).apply { balance = balance.subtract(operation.kind.signed(operation.money)) }
            target.balance = target.balance.add(operation.kind.signed(operation.money))
        }
        operation.account = target.uid
        emit(11, mapOf("uid" to operation.uid, "accUid" to target.uid, "moneyBack2" to gate))
    }

    private fun redateOperation(operation: Operation, backwards: Boolean) {
        val days = 1L + random.nextInt(30)
        operation.date = within(if (backwards) operation.date.minusDays(days) else operation.date.plusDays(days))
        emit(11, mapOf("uid" to operation.uid, "date" to format(operation.date)))
    }

    private fun recategorise(operation: Operation) {
        val target = categories.values.filter { it.kind == operation.kind && it.uid != operation.category }.random()
        operation.category = target.uid
        emit(11, mapOf("uid" to operation.uid, "catUid" to target.uid))
    }

    private fun deleteOperation(operation: Operation, gate: Int) {
        if (gate == 1) accounts.getValue(operation.account).apply { balance = balance.subtract(operation.kind.signed(operation.money)) }
        operations.remove(operation.uid)
        emit(12, mapOf("uid" to operation.uid, "moneyBack" to gate))
    }

    private fun randomTransfer() {
        val from = accounts.values.random()
        createTransfer(from, accounts.values.filter { it.uid != from.uid }.random())
    }

    private fun createTransfer(from: Account, to: Account, money: BigDecimal = transferAmount(from.currency)): Transfer {
        val transfer = Transfer(nextTransferUid++, from.uid, to.uid, money, convert(money, from.currency, to.currency), tick())
        transfers[transfer.uid] = transfer
        apply(transfer, 1)
        emit(50, buildMap {
            put("uid", transfer.uid)
            put("accFrom", from.uid)
            put("accTo", to.uid)
            put("money", json(money))
            transfer.moneyTo?.let { put("moneyTo", json(it)) }
            put("date", format(transfer.date))
        })
        return transfer
    }

    // A cross-currency transfer always restates moneyTo, or the vendor resets it; only the note-only update below leaves it out.
    private fun updateTransferMoney(transfer: Transfer) {
        apply(transfer, -1)
        val currency = currencyOf(transfer.from) ?: currencyOf(transfer.to)!!
        transfer.money = generateSequence { transferAmount(currency) }.first { it != transfer.money }
        val from = currencyOf(transfer.from)
        val to = currencyOf(transfer.to)
        if (transfer.moneyTo != null && from != null && to != null) transfer.moneyTo = convert(transfer.money, from, to)
        apply(transfer, 1)
        emit(51, buildMap {
            put("uid", transfer.uid)
            put("money", json(transfer.money))
            transfer.moneyTo?.let { put("moneyTo", json(it)) }
        })
    }

    private fun updateTransferNote(transfer: Transfer) {
        apply(transfer, -1)
        transfer.moneyTo = null
        apply(transfer, 1)
        emit(51, mapOf("uid" to transfer.uid, "note" to NOTES.random()))
    }

    private fun repointSource(transfer: Transfer, source: Account) {
        apply(transfer, -1)
        transfer.from = source.uid
        apply(transfer, 1)
        emit(51, buildMap {
            put("uid", transfer.uid)
            transfer.moneyTo?.let { put("moneyTo", json(it)) }
            put("accFrom", source.uid)
        })
    }

    private fun deleteTransfer(transfer: Transfer) {
        apply(transfer, -1)
        transfers.remove(transfer.uid)
        emit(52, mapOf("uid" to transfer.uid))
    }

    private fun apply(transfer: Transfer, direction: Int) {
        accounts[transfer.from]?.apply { balance = balance.subtract(transfer.money.multiply(BigDecimal(direction))) }
        accounts[transfer.to]?.apply { balance = balance.add((transfer.moneyTo ?: transfer.money).multiply(BigDecimal(direction))) }
    }

    private fun currencyOf(uid: Long) = accounts[uid]?.currency

    private fun amount(kind: Kind, currency: Currency): BigDecimal {
        val base = when (kind) {
            Kind.EXPENSE -> if (random.nextBoolean()) money(1 + random.nextInt(80)) else cents(100 + random.nextLong(7900))
            Kind.INCOME -> if (random.nextBoolean()) money(50 + random.nextInt(450)) else cents(5000 + random.nextLong(45000))
        }
        return base.multiply(currency.scale).setScale(2, RoundingMode.HALF_UP)
    }

    private fun transferAmount(currency: Currency) =
        money(50 + 10 * random.nextInt(30)).multiply(currency.scale).setScale(2, RoundingMode.HALF_UP)

    private fun convert(money: BigDecimal, from: Currency, to: Currency): BigDecimal? =
        if (from == to) null else money.multiply(to.perEuro).divide(from.perEuro, 2, RoundingMode.HALF_UP)

    private fun tick(): LocalDateTime {
        clock = clock.plusMinutes(10L + random.nextInt(80))
        return clock
    }

    private fun backdated() = within(tick().minusDays(1L + random.nextInt(30)))

    private fun within(date: LocalDateTime): LocalDateTime = when {
        date.toLocalDate().isBefore(size.start) -> size.start.atTime(date.toLocalTime())
        date.toLocalDate().isAfter(size.end) -> size.end.atTime(date.toLocalTime())
        else -> date
    }

    private fun randomOperation() = operations.values.random()

    private fun account(name: String) = accounts.values.single { it.name == name }
    private fun category(name: String) = categories.values.single { it.name == name }
    private fun group(name: String) = groups.values.single { it.name == name }

    private fun <T> Collection<T>.random(): T = elementAt(random.nextInt(size))
    private fun IntRange.random(): Int = first + random.nextInt(last - first + 1)

    private fun emit(type: Int, params: Map<String, Any>) {
        val uid = rows.size + 1L
        rows.add(ChronicleRow(uid, 1L + random.nextInt(1_999_999_999), type, PARAMS.writeValueAsString(params)))
    }

    private fun expected() = ExpectedImport(
        size = size.name,
        seed = size.seed,
        rows = rows.size,
        accounts = createdAccounts.map {
            ExpectedAccount(it.uid, it.name, it.currency.alpha, it.archived, if (it.archived) money(0) else it.balance)
        },
        categories = groups.size - 2 + categories.size,
        operations = operations.size,
        transfers = transfers.size,
        diagnostics = 0,
    )

    private enum class Kind(val code: Int, val root: Long) {
        EXPENSE(1, EXPENSE_ROOT),
        INCOME(2, INCOME_ROOT);

        fun signed(amount: BigDecimal): BigDecimal = if (this == EXPENSE) amount.negate() else amount
    }

    private class Currency(val numeric: Int, val alpha: String, val perEuro: BigDecimal, val scale: BigDecimal)

    private class Account(val uid: Long, val name: String, val currency: Currency, var balance: BigDecimal) {
        var archived = false
    }

    private class Group(val uid: Long, val kind: Kind, var name: String) {
        val items = ArrayList<Item>()
    }

    private data class Item(val uid: Long, val isGroup: Boolean)

    private class Category(val uid: Long, val kind: Kind, var name: String, var group: Long)

    private class Operation(
        val uid: Long,
        val kind: Kind,
        var account: Long,
        var category: Long,
        var money: BigDecimal,
        var date: LocalDateTime,
    )

    private class Transfer(
        val uid: Long,
        var from: Long,
        var to: Long,
        var money: BigDecimal,
        var moneyTo: BigDecimal?,
        val date: LocalDateTime,
    )

    private companion object {
        const val EXPENSE_ROOT = 1L
        const val INCOME_ROOT = 2L
        const val ORPHANED = 0L

        val EUR = Currency(978, "EUR", BigDecimal("1"), BigDecimal("1"))
        val USD = Currency(840, "USD", BigDecimal("1.08"), BigDecimal("1"))
        val RSD = Currency(941, "RSD", BigDecimal("117.20"), BigDecimal("100"))

        val NOTES = listOf("weekly shop", "with friends", "monthly", "refund pending", "birthday", "online order")

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
        val PARAMS: JsonMapper = JsonMapper.builder().build()

        fun money(units: Int): BigDecimal = BigDecimal(units).setScale(2)
        fun cents(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)
        fun format(date: LocalDateTime): String = date.format(DATE)

        // The source mixes JSON ints and floats in one field: a whole amount is written as an int.
        fun json(money: BigDecimal): Any =
            money.stripTrailingZeros().let { if (it.scale() <= 0) it.longValueExact() else it }
    }
}
