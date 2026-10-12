package com.github.melancholic.fintrace.importer.service.interpreter

internal sealed interface BlockStamp
internal class OpeningStamp(val account: AccountState) : BlockStamp
internal class AssignmentStamp(val anchor: AnchorState) : BlockStamp