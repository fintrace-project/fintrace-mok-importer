package com.github.melancholic.fintrace.importer.domain

data class ChronicleDump(
    val operations: List<RawMokOperation>,
    val renumbering: RenumberingWarning?,
)

data class RenumberingWarning(
    val sequence: Long?,
    val maxUid: Long,
)
