import java.io.IOException
import java.net.URI

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.openapi.generator") version "7.14.0"
}

group = "com.github.melancholic"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

	// Plain JDBC over the uploaded file: the dump is per-request input, not the service's datasource.
	implementation("org.xerial:sqlite-jdbc:3.53.4.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The import contract's DTOs, generated from the spec Core attaches to its GitHub Release at
// `coreApiVersion`. `-PcoreSpec=<path>` builds against an unreleased one, e.g. ../fintrace-core/openapi.json.
val coreApiVersion: String by project
val coreSpecOverride = providers.gradleProperty("coreSpec")
val generatedContract = layout.buildDirectory.dir("generated/fintrace-core")
val downloadedCoreSpec = layout.buildDirectory.file("core-api/$coreApiVersion/openapi.json")

val fetchCoreSpec by tasks.registering {
	description = "Downloads Core's OpenAPI spec for coreApiVersion."
	onlyIf { !coreSpecOverride.isPresent }
	inputs.property("coreApiVersion", coreApiVersion)
	outputs.file(downloadedCoreSpec)
	doLast {
		val url = "https://github.com/fintrace-project/fintrace-core/releases/download/v$coreApiVersion/openapi.json"
		val target = downloadedCoreSpec.get().asFile.apply { parentFile.mkdirs() }
		try {
			URI(url).toURL()
				.openStream()
				.use { input -> target.outputStream().use { input.copyTo(it) } }
		} catch (e: IOException) {
			target.delete()
			throw GradleException(
				"Cannot fetch $url - is Core v$coreApiVersion released? Use -PcoreSpec=<path> for an unreleased spec.",
				e
			)
		}
	}
}

openApiGenerate {
	generatorName = "kotlin"
	inputSpec = coreSpecOverride.map { file(it).path }.orElse(downloadedCoreSpec.map { it.asFile.path })
	outputDir = generatedContract.get().asFile.path
	modelPackage = "com.github.melancholic.fintrace.importer.contract"
	modelFilesConstrainedTo = listOf(
		"ImportEnvelopRequest", "ImportPayloadRequest", "ImportAccountRequest", "ImportCategoryRequest",
		"ImportOperationRequest", "ImportTransferRequest", "ImportTransferLegRequest",
		"ImportBalanceAnchorRequest", "ImportDiagnosticRequest", "ImportJobResponse",
		"ImportDiagnosticDTO", "ImportProblemDTO",
	)
	generateModelDocumentation = false
	generateModelTests = false
	configOptions = mapOf(
		"serializationLibrary" to "jackson",
		// Floating wall-clock, never zoned: the default OffsetDateTime would invent an offset.
		"dateLibrary" to "java8-localdatetime",
		"enumPropertyNaming" to "UPPERCASE",
		"sourceFolder" to "src/main/kotlin",
	)
}

sourceSets.main {
	kotlin.srcDir(generatedContract.map { it.dir("src/main/kotlin") })
}

tasks.openApiGenerate {
	dependsOn(fetchCoreSpec)
}

tasks.compileKotlin {
	dependsOn(tasks.openApiGenerate)
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.jar {
	enabled = false
}

springBoot {
	buildInfo { excludes = setOf("time") }
}