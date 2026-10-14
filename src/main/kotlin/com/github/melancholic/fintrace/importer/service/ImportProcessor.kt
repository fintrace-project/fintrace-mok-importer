package com.github.melancholic.fintrace.importer.service

import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.domain.ChronicleSnapshot
import com.github.melancholic.fintrace.importer.integration.FintraceCoreAdapter
import com.github.melancholic.fintrace.importer.service.interpreter.ChronicleInterpreter
import com.github.melancholic.fintrace.importer.util.TimestampProvider
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.*

interface ImportProcessor {
    fun processImport(workspaceId: UUID, dump: Path): ImportJobResponse
}

@Service
class ImportProcessorImpl(
    private val timestampProvider: TimestampProvider,
    private val mokDumpLoader: MokOperationsLoader,
    private val chronicleParser: ChronicleParser,
    private val interpreter: ChronicleInterpreter,
    private val importRequestFactory: ImportRequestFactory,
    private val fintraceCoreAdapter: FintraceCoreAdapter
) : ImportProcessor {

    override fun processImport(workspaceId: UUID, dump: Path): ImportJobResponse {
        val importStartTime = timestampProvider.now()
        val workspace = fintraceCoreAdapter.getWorkspace(workspaceId)

        val chronicleSnapshot = interpretDump(dump, workspace.defaultCurrency, importStartTime)
        val request = importRequestFactory.buildImportRequest(chronicleSnapshot)

        return fintraceCoreAdapter.importWorkspaceData(workspaceId, request)
    }

    private fun interpretDump(dump: Path, defaultCurrency: String, importStartTime: LocalDateTime): ChronicleSnapshot {
        val chronicleDump = mokDumpLoader.load(dump)
        val parsedChronicle = chronicleParser.parse(chronicleDump)
        return interpreter.interpretChronicle(parsedChronicle, importStartTime, defaultCurrency)
    }
}
