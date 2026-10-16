package com.github.melancholic.fintrace.importer.facade

import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.exception.InvalidMokDumpException
import com.github.melancholic.fintrace.importer.service.ImportProcessor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.absolute

interface MokImportFacade {
    fun importMokDumpFile(workspaceId: UUID, file: MultipartFile): ImportJobResponse
}

@Component
class MokImportFacadeImpl(
    private val importProcessor: ImportProcessor
) : MokImportFacade {

    override fun importMokDumpFile(workspaceId: UUID, file: MultipartFile): ImportJobResponse {
        validate(file)
        log.info { "Import upload of ${file.size} bytes" }
        return withTempCopy(file) { importProcessor.processImport(workspaceId, it) }
    }

    private fun validate(file: MultipartFile) {
        if (file.isEmpty) throw InvalidMokDumpException("The uploaded file is empty")
        val header = file.inputStream.use { it.readNBytes(SQLITE_HEADER.size) }
        if (!header.contentEquals(SQLITE_HEADER)) throw InvalidMokDumpException("The uploaded file is not a .mok dump")
    }

    private fun <T> withTempCopy(file: MultipartFile, block: (Path) -> T): T {
        val dir = Files.createTempDirectory("mok-import-")
        try {
            val dump = dir.resolve("dump.mok")
            file.transferTo(dump)
            log.debug { "Saving ${dump.fileName} (${file.size} bytes) to ${dir.toAbsolutePath()}" }
            return block(dump)
        } finally {
            if (dir.toFile().deleteRecursively()) {
                log.debug { "Deleted ${dir.absolute()}" }
            } else {
                log.warn { "Failed to delete ${dir.absolute()}" }
            }
        }
    }


    private companion object {
        private val log = KotlinLogging.logger {}
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }

}
