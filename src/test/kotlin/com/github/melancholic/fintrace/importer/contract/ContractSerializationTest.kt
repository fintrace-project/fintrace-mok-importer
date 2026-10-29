package com.github.melancholic.fintrace.importer.contract

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/** The generated DTOs as Core reads them: floating wall-clock timestamps, exact amounts, enum names. */
class ContractSerializationTest {

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `an operation serialises with a zoneless timestamp and an exact amount`() {
        val operation = ImportOperationRequest(
            id = UUID.fromString("0199c9f0-0000-7000-8000-000000000001"),
            occurredAt = LocalDateTime.of(2025, 1, 5, 23, 6, 24),
            amount = BigDecimal("999.99"),
            kind = OperationKind.EXPENSE,
            accountId = UUID.fromString("0199c9f0-0000-7000-8000-000000000002"),
        )

        val json = mapper.readTree(mapper.writeValueAsString(operation))

        assertEquals("2025-01-05T23:06:24", json["occurredAt"].asString())
        assertEquals(BigDecimal("999.99"), json["amount"].decimalValue())
        assertEquals("EXPENSE", json["kind"].asString())
    }

    @Test
    fun `a job response reads back with its problems`() {
        val job = mapper.readValue<ImportJobResponse>(
            """
            {"id":"0199c9f0-0000-7000-8000-000000000003","workspaceId":"0199c9f0-0000-7000-8000-000000000004",
             "status":"FAILED","startedBy":"0199c9f0-0000-7000-8000-000000000005",
             "startedAt":"2025-01-05T23:06:24","finishedAt":null,"importerName":"mok","importerVersion":"0.1.0",
             "message":null,"accounts":0,"categories":0,"operations":0,"transfers":0,"anchors":0,"diagnostics":[],
             "problems":[{"code":"UNRESOLVED_REFERENCE","message":"Unresolved accountId","aggregateType":"OPERATION",
                          "field":"accountId","affectedIDs":["0199c9f0-0000-7000-8000-000000000002"],"affectedCount":1}]}
            """
        )

        assertEquals(ImportJobStatus.FAILED, job.status)
        assertEquals(LocalDateTime.of(2025, 1, 5, 23, 6, 24), job.startedAt)
        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, job.problems.single().code)
    }
}
