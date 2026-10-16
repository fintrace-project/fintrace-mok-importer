package com.github.melancholic.fintrace.importer.facade

import com.github.melancholic.fintrace.importer.ChronicleFixture
import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.exception.InvalidMokDumpException
import com.github.melancholic.fintrace.importer.service.ImportProcessor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MokImportFacadeTest {

    @TempDir
    lateinit var dir: Path

    private val calls = mutableListOf<Path>()
    private val job = mock(ImportJobResponse::class.java)
    private val facade = MokImportFacadeImpl(object : ImportProcessor {
        override fun processImport(workspaceId: UUID, dump: Path): ImportJobResponse {
            assertTrue(Files.exists(dump), "the dump must exist while it is processed")
            calls.add(dump)
            return job
        }
    })

    @Test
    fun `passes a SQLite file to the processor, whatever it is called, and deletes the copy`() {
        val result = facade.importMokDumpFile(WORKSPACE_ID, upload("backup", Files.readAllBytes(dump())))

        assertEquals(job, result)
        assertFalse(Files.exists(calls.single().parent), "the temporary directory must be deleted")
    }

    @Test
    fun `accepts a file without a name`() {
        facade.importMokDumpFile(WORKSPACE_ID, upload(null, Files.readAllBytes(dump())))

        assertEquals(1, calls.size)
    }

    @Test
    fun `refuses an empty file without calling the processor`() {
        assertThrows<InvalidMokDumpException> { facade.importMokDumpFile(WORKSPACE_ID, upload("dump.mok", ByteArray(0))) }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `refuses a file that is not SQLite, even named mok, without calling the processor`() {
        val e = assertThrows<InvalidMokDumpException> {
            facade.importMokDumpFile(WORKSPACE_ID, upload("dump.mok", "not a database at all".toByteArray()))
        }
        assertTrue(e.message!!.contains("not a .mok dump"), e.message)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `refuses a file shorter than the SQLite header`() {
        assertThrows<InvalidMokDumpException> { facade.importMokDumpFile(WORKSPACE_ID, upload("dump.mok", "SQLite".toByteArray())) }
    }

    @Test
    fun `deletes the copy when the processor throws`() {
        var seen: Path? = null
        val failing = MokImportFacadeImpl(object : ImportProcessor {
            override fun processImport(workspaceId: UUID, dump: Path): ImportJobResponse {
                seen = dump
                throw InvalidMokDumpException("refused")
            }
        })

        assertThrows<InvalidMokDumpException> { failing.importMokDumpFile(WORKSPACE_ID, upload("dump.mok", Files.readAllBytes(dump()))) }
        assertFalse(Files.exists(seen!!.parent), "the temporary directory must be deleted")
    }

    private fun dump() = ChronicleFixture(dir.resolve("source.mok")).write()

    private fun upload(name: String?, bytes: ByteArray) = MockMultipartFile("file", name, "application/octet-stream", bytes)

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000aa")
    }
}
