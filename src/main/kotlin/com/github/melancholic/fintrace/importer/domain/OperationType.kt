package com.github.melancholic.fintrace.importer.domain

enum class OperationType(val code: Int) {
    MOK_VERSION(1),

    ADDED_NEW_OPERATION(10),
    UPDATED_OPERATION(11),
    DELETED_OPERATION(12),

    ADDED_NEW_ACCOUNT(20),
    UPDATED_ACCOUNT(21),
    DELETED_ACCOUNT(22),
    REORDERED_ACCOUNT(23),

    ADDED_NEW_CATEGORIES_GROUP(30),
    UPDATED_CATEGORIES_GROUP(31),
    DELETED_CATEGORIES_GROUP(32),
    MOVED_CATEGORIES_GROUP(33),

    ADDED_NEW_CATEGORY(40),
    UPDATED_CATEGORY(41),
    DELETED_CATEGORY(42),
    MOVED_CATEGORY(43),
    MOVED_ITEM(45),


    ADDED_NEW_TRANSFER(50),
    UPDATED_TRANSFER(51),
    DELETED_TRANSFER(52),

    DELETED(100),

    ACCOUNT_MONEY_VERIFICATION(110);


    companion object {
        private val values = entries.associateBy { it.code }

        fun fromOrNull(code: Int?): OperationType? = code?.let { values[it] }
    }
}