package com.github.melancholic.fintrace.importer.domain

import com.github.melancholic.fintrace.importer.util.asFlag
import com.github.melancholic.fintrace.importer.util.asMoney
import com.github.melancholic.fintrace.importer.util.parseDateTime
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDateTime

abstract class MokOperation(
    val num: Long,
    val type: OperationType,
    val date: LocalDateTime? = null
)

data class RawMokOperation(
    val num: Long,
    val type: Int?,
    val rawData: String?
)

class ChronicleVersion(
    num: Long,
    val version: Int
) : MokOperation(num, OperationType.MOK_VERSION) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("version").asInt()
    )

    override fun toString() = "ChronicleVersion(num=$num, version=$version)"
}

class AddedNewAccount(
    num: Long,
    val uid: Long,
    val name: String,
    val money: BigDecimal,
    val currency: Int?,
    val note: String
) : MokOperation(num, OperationType.ADDED_NEW_ACCOUNT) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("name").asString(),
        json.get("money").asMoney(),
        json.get("currency")?.asInt(),
        json.get("note")?.asString() ?: ""
    )

    override fun toString() =
        "AddedNewAccount(num=$num, uid=$uid, name='$name', money=$money, currency=$currency, note='$note')"
}

class UpdatedAccount(
    num: Long,
    val uid: Long,
    val name: String?,
    val money: BigDecimal?,
    val currency: Int?,
    val note: String?
) : MokOperation(num, OperationType.UPDATED_ACCOUNT) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("name")?.asString(),
        json.get("money")?.asMoney(),
        json.get("currency")?.asInt(),
        json.get("note")?.asString()
    )

    override fun toString() =
        "UpdatedAccount(num=$num, uid=$uid, name=$name, money=$money, currency=$currency, note=$note)"
}

class AddedNewTransfer(
    num: Long,
    date: LocalDateTime?,
    val uid: Long,
    val money: BigDecimal,
    val moneyTo: BigDecimal?,
    val accFrom: Long,
    val accTo: Long,
    val note: String
) : MokOperation(num, OperationType.ADDED_NEW_TRANSFER, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("date")?.let { parseDateTime(it.asString()) },
        json.get("uid").asLong(),
        json.get("money").asMoney(),
        json.get("moneyTo")?.asMoney(),
        json.get("accFrom").asLong(),
        json.get("accTo").asLong(),
        json.get("note")?.asString() ?: ""
    )

    override fun toString() =
        "AddedNewTransfer(num=$num, date=$date, uid=$uid, money=$money, moneyTo=$moneyTo, accFrom=$accFrom, accTo=$accTo, note='$note')"
}

class UpdatedTransfer(
    num: Long,
    date: LocalDateTime?,
    val uid: Long,
    val money: BigDecimal?,
    val moneyTo: BigDecimal?,
    val accFrom: Long?,
    val accTo: Long?,
    val note: String?
) : MokOperation(num, OperationType.UPDATED_TRANSFER, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("date")?.let { parseDateTime(it.asString()) },
        json.get("uid").asLong(),
        json.get("money")?.asMoney(),
        json.get("moneyTo")?.asMoney(),
        json.get("accFrom")?.asLong(),
        json.get("accTo")?.asLong(),
        json.get("note")?.asString()
    )

    override fun toString() =
        "UpdatedTransfer(num=$num, date=$date, uid=$uid, money=$money, moneyTo=$moneyTo, accFrom=$accFrom, accTo=$accTo, note=$note)"
}

class DeletedTransfer(
    num: Long,
    date: LocalDateTime?,
    val uid: Long,
) : MokOperation(num, OperationType.DELETED_TRANSFER, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("date")?.let { parseDateTime(it.asString()) },
        json.get("uid").asLong(),
    )

    override fun toString() = "DeletedTransfer(num=$num, date=$date, uid=$uid)"
}

class AddNewTransaction(
    num: Long,
    date: LocalDateTime,
    val uid: Long,
    val opType: CategoryType,
    val account: Long,
    val category: Long?,
    val money: BigDecimal,
    val note: String
) : MokOperation(num, OperationType.ADDED_NEW_OPERATION, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        parseDateTime(json.get("date").asString()),
        json.get("uid").asLong(),
        CategoryType.from(json.get("opType").asInt()),
        json.get("accUid").asLong(),
        json.get("catUid")?.asLong(),
        json.get("money").asMoney(),
        json.get("note")?.asString() ?: ""
    )

    override fun toString() =
        "AddNewTransaction(num=$num, date=$date, uid=$uid, opType=$opType, account=$account, category=$category, money=$money, note='$note')"
}

class UpdateTransaction(
    num: Long,
    date: LocalDateTime?,
    val uid: Long,
    val account: Long?,
    val category: Long?,
    val money: BigDecimal?,
    val moneyBack: Boolean?,
    val moneyBack2: Boolean?,
    val note: String?
) : MokOperation(num, OperationType.UPDATED_OPERATION, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("date")?.asString()?.let {parseDateTime(it)},
        json.get("uid").asLong(),
        json.get("accUid")?.asLong(),
        json.get("catUid")?.asLong(),
        json.get("money")?.asMoney(),
        json.get("moneyBack")?.asInt()?.let { it == 1 },
        json.get("moneyBack2")?.asInt()?.let { it == 1 },
        json.get("note")?.asString()
    )

    override fun toString() =
        "UpdateTransaction(num=$num, date=$date, uid=$uid, account=$account, category=$category, money=$money, moneyBack=$moneyBack, moneyBack2=$moneyBack2, note=$note)"
}

class DeleteTransaction(
    num: Long,
    date: LocalDateTime?,
    val uid: Long,
    val moneyBack: Boolean?
) : MokOperation(num, OperationType.DELETED_OPERATION, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("date")?.let { parseDateTime(it.asString()) },
        json.get("uid").asLong(),
        json.get("moneyBack")?.asInt()?.let { it == 1 }
    )

    override fun toString() = "DeleteTransaction(num=$num, date=$date, uid=$uid, moneyBack=$moneyBack)"
}

class DeletedAccount(
    num: Long,
    val uid: Long,
    val moneyBack: Boolean? = null
) : MokOperation(num, OperationType.DELETED_ACCOUNT) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("moneyBack")?.asInt()?.let { it == 1 }
    )

    override fun toString() = "DeletedAccount(num=$num, uid=$uid, moneyBack=$moneyBack)"
}

class AccountMoneyVerification(
    num: Long,
    date: LocalDateTime?,
    val uid: Long,
    val money: BigDecimal
) : MokOperation(num, OperationType.ACCOUNT_MONEY_VERIFICATION, date) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("date")?.let { parseDateTime(it.asString()) },
        json.get("uid").asLong(),
        json.get("money").asMoney()
    )

    override fun toString() = "AccountMoneyVerification(num=$num, date=$date, uid=$uid, money=$money)"
}

class AddedNewCategoriesGroup(
    num: Long,
    val uid: Long,
    val name: String,
    val opType: CategoryType
) : MokOperation(num, OperationType.ADDED_NEW_CATEGORIES_GROUP) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("name").asString(),
        CategoryType.from(json.get("opType").asInt())
    )

    override fun toString() = "AddedNewCategoriesGroup(num=$num, uid=$uid, name='$name', opType=$opType)"
}

class UpdatedCategoriesGroup(
    num: Long,
    val uid: Long,
    val name: String?
) : MokOperation(num, OperationType.UPDATED_CATEGORIES_GROUP) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("name")?.asString()
    )

    override fun toString() = "UpdatedCategoriesGroup(num=$num, uid=$uid, name=$name)"
}

class DeletedCategoriesGroup(
    num: Long,
    val uid: Long
) : MokOperation(num, OperationType.DELETED_CATEGORIES_GROUP) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong()
    )

    override fun toString() = "DeletedCategoriesGroup(num=$num, uid=$uid)"
}

class AddedNewCategory(
    num: Long,
    val uid: Long,
    val name: String,
    val opType: CategoryType,
    val group: Long
) : MokOperation(num, OperationType.ADDED_NEW_CATEGORY) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("name").asString(),
        CategoryType.from(json.get("opType").asInt()),
        json.get("groupUid").asLong()
    )

    override fun toString() =
        "AddedNewCategory(num=$num, uid=$uid, name='$name', opType=$opType, group=$group)"
}

class UpdatedCategory(
    num: Long,
    val uid: Long,
    val name: String?
) : MokOperation(num, OperationType.UPDATED_CATEGORY) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("name")?.asString()
    )

    override fun toString() = "UpdatedCategory(num=$num, uid=$uid, name=$name)"
}

class DeletedCategory(
    num: Long,
    val uid: Long,
    val moneyBack: Boolean?
) : MokOperation(num, OperationType.DELETED_CATEGORY) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("moneyBack")?.asFlag()
    )

    override fun toString() = "DeletedCategory(num=$num, uid=$uid, moneyBack=$moneyBack)"
}

class MovedItem(
    num: Long,
    val uid: Long,
    val isGroup: Boolean,
    val newGroup: Long
) : MokOperation(num, OperationType.MOVED_ITEM) {
    constructor(num: Long, json: JsonNode) : this(
        num,
        json.get("uid").asLong(),
        json.get("isGroup")?.asFlag() ?: false,
        json.get("newGroupUid").asLong()
    )

    override fun toString() = "MovedItem(num=$num, uid=$uid, isGroup=$isGroup, newGroup=$newGroup)"
}