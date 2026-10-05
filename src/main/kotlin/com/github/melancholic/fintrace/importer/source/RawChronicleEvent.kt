package com.github.melancholic.fintrace.importer.source

/** One `Chronicle` row as stored; nullable wherever the DDL allows, interpretation is downstream. */
data class RawChronicleEvent(
    val uid: Long,
    val badge: Long?,
    val type: Int?,
    val params: String?,
)
