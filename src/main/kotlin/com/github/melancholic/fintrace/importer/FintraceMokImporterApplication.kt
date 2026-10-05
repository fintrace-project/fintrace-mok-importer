package com.github.melancholic.fintrace.importer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FintraceMokImporterApplication

fun main(args: Array<String>) {
    runApplication<FintraceMokImporterApplication>(*args)
}
