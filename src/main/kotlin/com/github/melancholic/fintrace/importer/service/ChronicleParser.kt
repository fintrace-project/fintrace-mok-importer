package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.domain.*
import com.github.melancholic.fintrace.importer.domain.OperationType.*
import com.github.melancholic.fintrace.importer.exception.InvalidChronicleException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

interface ChronicleParser {
    fun parse(dump: ChronicleDump): ParsedChronicle
}

@Service
class ChronicleParserImpl(private val mapper: ObjectMapper) : ChronicleParser {

    override fun parse(dump: ChronicleDump): ParsedChronicle {
        val events = ArrayList<MokOperation>()
        val skipped = LinkedHashMap<Int?, Long>()
        var version: Int? = null

        for (raw in dump.operations) {
            val constructor = OperationType.fromOrNull(raw.type)
                ?.let { CONSTRUCTORS[it] }

            if (constructor == null) {
                skipped.compute(raw.type) { _, v ->  v?.let {v+1} ?: 1 }
                continue
            }

            when (val event = construct(raw, constructor)) {
                is ChronicleVersion -> {
                    if (event.version > SUPPORTED_VERSION) {
                        throw InvalidChronicleException(
                            "Chronicle version ${event.version} at uid ${raw.num} is newer than the supported $SUPPORTED_VERSION"
                        )
                    }
                    version = maxOf(version ?: event.version, event.version)
                }

                else -> events.add(event)
            }
        }

        val diagnostics = skipped.map { (type, count) ->
            Diagnostic(Diagnostic.Severity.WARNING, Diagnostic.Code.UNSUPPORTED_RECORD, count, "type=$type")
        }
        if (diagnostics.isNotEmpty()) {
            logger.warn { "Skipped unsupported chronicle rows: ${skipped.entries.joinToString { "type=${it.key} ×${it.value}" }}" }
        }

        return ParsedChronicle(events, version, diagnostics, dump.renumbering)
    }

    private fun construct(raw: RawMokOperation, constructor: (Long, JsonNode) -> MokOperation): MokOperation {
        val params = raw.rawData ?: throw InvalidChronicleException("Chronicle row uid ${raw.num} (type ${raw.type}) has no params")
        val json = try {
            mapper.readTree(params)
        } catch (e: JacksonException) {
            throw InvalidChronicleException("Chronicle row uid ${raw.num} (type ${raw.type}) has unparseable params", e)
        }
        return try {
            constructor(raw.num, json)
        } catch (e: RuntimeException) {
            throw InvalidChronicleException("Chronicle row uid ${raw.num} (type ${raw.type}) is malformed: $params", e)
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val SUPPORTED_VERSION = 3

        private val CONSTRUCTORS: Map<OperationType, (Long, JsonNode) -> MokOperation> = mapOf(
            MOK_VERSION to ::ChronicleVersion,
            ADDED_NEW_OPERATION to ::AddNewTransaction,
            UPDATED_OPERATION to ::UpdateTransaction,
            DELETED_OPERATION to ::DeleteTransaction,
            ADDED_NEW_ACCOUNT to ::AddedNewAccount,
            UPDATED_ACCOUNT to ::UpdatedAccount,
            DELETED_ACCOUNT to ::DeletedAccount,
            ADDED_NEW_CATEGORIES_GROUP to ::AddedNewCategoriesGroup,
            UPDATED_CATEGORIES_GROUP to ::UpdatedCategoriesGroup,
            DELETED_CATEGORIES_GROUP to ::DeletedCategoriesGroup,
            ADDED_NEW_CATEGORY to ::AddedNewCategory,
            UPDATED_CATEGORY to ::UpdatedCategory,
            DELETED_CATEGORY to ::DeletedCategory,
            MOVED_ITEM to ::MovedItem,
            ADDED_NEW_TRANSFER to ::AddedNewTransfer,
            UPDATED_TRANSFER to ::UpdatedTransfer,
            DELETED_TRANSFER to ::DeletedTransfer,
            ACCOUNT_MONEY_VERIFICATION to ::AccountMoneyVerification,
        )
    }
}
