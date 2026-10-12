package com.github.melancholic.fintrace.importer.service.interpreter

import com.github.melancholic.fintrace.importer.domain.*
import com.github.melancholic.fintrace.importer.domain.Diagnostic.Code
import com.github.melancholic.fintrace.importer.service.UUIDGenerator
import org.springframework.stereotype.Component

internal sealed interface CategoryApplier {
    fun createGroupCategory(context: ChronicleSnapshotContext, event: AddedNewCategoriesGroup)
    fun updateGroupCategory(context: ChronicleSnapshotContext, event: UpdatedCategoriesGroup)
    fun removeGroupCategory(context: ChronicleSnapshotContext, event: DeletedCategoriesGroup)
    fun createCategory(context: ChronicleSnapshotContext, event: AddedNewCategory)
    fun updateCategory(context: ChronicleSnapshotContext, event: UpdatedCategory)
    fun removeCategory(context: ChronicleSnapshotContext, event: DeletedCategory)
    fun moveCategory(context: ChronicleSnapshotContext, event: MovedItem)
    fun emitCategories(context: ChronicleSnapshotContext): List<SnapshotCategory>
}

@Component
internal class CategoryApplierImpl(
    private val ids: UUIDGenerator,
) : AbstractApplier(), CategoryApplier {

    override fun createGroupCategory(context: ChronicleSnapshotContext, event: AddedNewCategoriesGroup) =
        create(context, context.groups, event.uid, event.name, event.opType, null, isGroup = true)

    override fun updateGroupCategory(context: ChronicleSnapshotContext, event: UpdatedCategoriesGroup) {
        event.name?.let { rename(context, context.groups, event.uid, it) }
    }

    override fun removeGroupCategory(context: ChronicleSnapshotContext, event: DeletedCategoriesGroup) {
        if (context.groups.remove(event.uid) == null) count(context, Code.SOURCE_ANOMALY, "unknown group referenced")
    }

    override fun createCategory(context: ChronicleSnapshotContext, event: AddedNewCategory) =
        create(context, context.categories, event.uid, event.name, event.opType, event.group, isGroup = false)

    override fun updateCategory(context: ChronicleSnapshotContext, event: UpdatedCategory) {
        event.name?.let { rename(context, context.categories, event.uid, it) }
    }

    override fun removeCategory(context: ChronicleSnapshotContext, event: DeletedCategory) {
        if (context.categories.remove(event.uid) == null) {
            count(context, Code.SOURCE_ANOMALY, "deletion of an unknown category ignored")
        } else {
            val iterator = context.operations.values.iterator()
            while (iterator.hasNext()) {
                val operation = iterator.next()
                if (operation.category == event.uid) {
                    if (event.moneyBack == true) operation.account.balance -= operation.signed()
                    iterator.remove()
                }
            }
        }
    }

    override fun moveCategory(context: ChronicleSnapshotContext, event: MovedItem) {
        val item = (if (event.isGroup) context.groups else context.categories)[event.uid]
        if (item == null) {
            count(context, Code.SOURCE_ANOMALY, "move of an unknown category ignored")
        } else {
            item.parent = event.newGroup
        }
    }

    override fun emitCategories(context: ChronicleSnapshotContext): List<SnapshotCategory> {
        val items = context.groups.values + context.categories.values
        val parentOf = HashMap<CategoryState, CategoryState?>()
        for (item in items) parentOf[item] = resolveParent(context, item)

        // A move cycle among groups would make Core refuse the payload; cut it at the item that closes it.
        for (item in parentOf.keys) {
            val seen = HashSet<CategoryState>()
            var current: CategoryState? = item
            while (current != null && seen.add(current)) current = parentOf[current]
            if (current === item) {
                parentOf[item] = null
                count(context, Code.SOURCE_ANOMALY, "category cycle cut")
            }
        }

        return items
            .map { SnapshotCategory(it.id, it.uid, it.isGroup, it.name, it.kind, parentOf[it]?.id) }
    }

    private fun create(
        context: ChronicleSnapshotContext,
        into: MutableMap<Long, CategoryState>,
        uid: Long,
        name: String,
        kind: CategoryType,
        parent: Long?,
        isGroup: Boolean,
    ) {
        if (into.containsKey(uid)) {
            count(context, Code.SOURCE_ANOMALY, "duplicate category creation ignored")
        } else {
            into[uid] = CategoryState(
                ids.nextUUID(),
                uid,
                isGroup,
                name(context, name, if (isGroup) "Group $uid" else "Category $uid"),
                kind,
                parent,
            )
        }
    }

    private fun rename(context: ChronicleSnapshotContext, from: Map<Long, CategoryState>, uid: Long, name: String) {
        val category = from[uid]
        if (category == null) {
            count(context, Code.SOURCE_ANOMALY, "rename of an unknown category ignored")
        } else {
            category.name = name(context, name, category.name)
        }
    }

    private fun resolveParent(context: ChronicleSnapshotContext, item: CategoryState): CategoryState? {
        val uid = item.parent
        val parent = uid?.let { context.groups[it] }
        return when {
            uid == null -> null
            uid == EXPENSE_ROOT || uid == INCOME_ROOT -> {
                val rootKind = if (uid == EXPENSE_ROOT) CategoryType.EXPENSE else CategoryType.INCOME
                if (rootKind != item.kind) {
                    count(context, Code.SOURCE_ANOMALY, "category under the other kind's root")
                }
                null
            }
            parent == null -> {
                count(context, Code.UNRESOLVED_REFERENCE, "category group no longer exists")
                null
            }
            parent.kind != item.kind -> {
                count(context, Code.SOURCE_ANOMALY, "category under a group of the other kind")
                null
            }
            else -> parent
        }
    }
}
