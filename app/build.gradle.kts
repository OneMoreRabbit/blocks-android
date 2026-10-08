import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

@CacheableTask
abstract class VerifyContractArtifacts : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractDirectory: DirectoryProperty

    @get:Input
    abstract val expectedArtifactCount: Property<Int>

    @TaskAction
    fun verifyArtifacts() {
        val directory = contractDirectory.get()
        val entries = directory.file("SHA256SUMS").asFile.readLines().filter(String::isNotBlank)
        check(entries.size == expectedArtifactCount.get()) {
            "Expected ${expectedArtifactCount.get()} Service API contract artifacts, got ${entries.size}"
        }
        val declaredNames = entries.map { it.split(Regex("\\s+"), limit = 2)[1] }.toSet()
        val deliveredNames = directory.asFile.listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .map { it.name }
            .toSet()
        check(deliveredNames == declaredNames) {
            "Service API contract artifact set differs: declared=$declaredNames delivered=$deliveredNames"
        }
        entries.forEach { line ->
            val (expected, name) = line.split(Regex("\\s+"), limit = 2)
            val artifact = directory.file(name).asFile
            check(artifact.isFile) { "Missing Service API contract artifact: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(digest == expected) {
                "Service API contract artifact hash mismatch for $name: expected $expected, got $digest"
            }
        }
    }
}

plugins {
    id("com.android.application")
    id("org.openapi.generator")
}

val serviceApiContractDir = rootProject.layout.projectDirectory.dir("contracts/blocks-service-api")
val serviceApiSpec = serviceApiContractDir.file("blocks-service-api-openapi-v0_8.json")
val legacyV07ServiceApiContractDir = rootProject.layout.projectDirectory.dir("contracts/blocks-service-api-legacy-v0_7")
val legacyV07ServiceApiSpec = legacyV07ServiceApiContractDir.file("blocks-service-api-openapi-v0_7.json")
val legacyV06ServiceApiContractDir = rootProject.layout.projectDirectory.dir("contracts/blocks-service-api-legacy-v0_6")
val legacyV06ServiceApiSpec = legacyV06ServiceApiContractDir.file("blocks-service-api-openapi-v0_6.json")
val legacyV04ServiceApiContractDir = rootProject.layout.projectDirectory.dir("contracts/blocks-service-api-legacy-v0_4")
val legacyV04ServiceApiSpec = legacyV04ServiceApiContractDir.file("blocks-service-api-openapi-v0_4.json")
val legacyServiceApiContractDir = rootProject.layout.projectDirectory.dir("contracts/blocks-service-api-legacy-v0_2")
val legacyServiceApiSpec = legacyServiceApiContractDir.file("blocks-service-api-openapi-v0_2.json")
val generatedServiceApi = layout.buildDirectory.dir("generated/openapi-v0_8")
val generatedLegacyV07ServiceApi = layout.buildDirectory.dir("generated/openapi-v0_7")
val generatedLegacyV06ServiceApi = layout.buildDirectory.dir("generated/openapi-v0_6")
val generatedLegacyV04ServiceApi = layout.buildDirectory.dir("generated/openapi-v0_4")
val generatedLegacyServiceApi = layout.buildDirectory.dir("generated/openapi-v0_2")

val verifyServiceApiContract by tasks.registering(VerifyContractArtifacts::class) {
    group = "verification"
    description = "Verifies the Atlas-delivered Service API 0.8 artifacts."
    contractDirectory.set(serviceApiContractDir)
    expectedArtifactCount.set(28)
}

val verifyLegacyV07ServiceApiContract by tasks.registering(VerifyContractArtifacts::class) {
    group = "verification"
    description = "Verifies the frozen Service API 0.7 compatibility artifacts."
    contractDirectory.set(legacyV07ServiceApiContractDir)
    expectedArtifactCount.set(26)
}

val verifyLegacyV06ServiceApiContract by tasks.registering(VerifyContractArtifacts::class) {
    group = "verification"
    description = "Verifies the frozen Service API 0.6 compatibility artifacts."
    contractDirectory.set(legacyV06ServiceApiContractDir)
    expectedArtifactCount.set(22)
}

val verifyLegacyV04ServiceApiContract by tasks.registering(VerifyContractArtifacts::class) {
    group = "verification"
    description = "Verifies the frozen Service API 0.4 compatibility artifacts."
    contractDirectory.set(legacyV04ServiceApiContractDir)
    expectedArtifactCount.set(15)
}

val verifyLegacyServiceApiContract by tasks.registering(VerifyContractArtifacts::class) {
    group = "verification"
    description = "Verifies the frozen Service API 0.2 compatibility artifacts."
    contractDirectory.set(legacyServiceApiContractDir)
    expectedArtifactCount.set(10)
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(serviceApiSpec.asFile.absolutePath)
    outputDir.set(generatedServiceApi.get().asFile.absolutePath)
    modelPackage.set("dev.blocks.android.api.generated.v08.model")
    packageName.set("dev.blocks.android.api.generated.v08")
    validateSpec.set(true)
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "library" to "jvm-okhttp4",
            "serializationLibrary" to "gson",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(
        mapOf(
            "apiDocs" to "false",
            "apis" to "false",
            "modelDocs" to "false",
            "models" to "",
            "modelTests" to "false",
            "supportingFiles" to "false",
        ),
    )
}

val openApiGenerateLegacyV07 by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    inputSpec.set(legacyV07ServiceApiSpec.asFile.absolutePath)
    outputDir.set(generatedLegacyV07ServiceApi.get().asFile.absolutePath)
    modelPackage.set("dev.blocks.android.api.generated.v07.model")
    packageName.set("dev.blocks.android.api.generated.v07")
    validateSpec.set(true)
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "library" to "jvm-okhttp4",
            "serializationLibrary" to "gson",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(
        mapOf(
            "apiDocs" to "false",
            "apis" to "false",
            "modelDocs" to "false",
            "models" to "",
            "modelTests" to "false",
            "supportingFiles" to "false",
        ),
    )
    dependsOn(verifyLegacyV07ServiceApiContract)
}

val openApiGenerateLegacyV06 by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    inputSpec.set(legacyV06ServiceApiSpec.asFile.absolutePath)
    outputDir.set(generatedLegacyV06ServiceApi.get().asFile.absolutePath)
    modelPackage.set("dev.blocks.android.api.generated.v06.model")
    packageName.set("dev.blocks.android.api.generated.v06")
    validateSpec.set(true)
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "library" to "jvm-okhttp4",
            "serializationLibrary" to "gson",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(
        mapOf(
            "apiDocs" to "false",
            "apis" to "false",
            "modelDocs" to "false",
            "models" to "",
            "modelTests" to "false",
            "supportingFiles" to "false",
        ),
    )
    dependsOn(verifyLegacyV06ServiceApiContract)
}

val openApiGenerateLegacyV04 by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    inputSpec.set(legacyV04ServiceApiSpec.asFile.absolutePath)
    outputDir.set(generatedLegacyV04ServiceApi.get().asFile.absolutePath)
    modelPackage.set("dev.blocks.android.api.generated.v04.model")
    packageName.set("dev.blocks.android.api.generated.v04")
    validateSpec.set(true)
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "library" to "jvm-okhttp4",
            "serializationLibrary" to "gson",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(
        mapOf(
            "apiDocs" to "false",
            "apis" to "false",
            "modelDocs" to "false",
            "models" to "",
            "modelTests" to "false",
            "supportingFiles" to "false",
        ),
    )
    dependsOn(verifyLegacyV04ServiceApiContract)
}

val openApiGenerateLegacyV02 by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    inputSpec.set(legacyServiceApiSpec.asFile.absolutePath)
    outputDir.set(generatedLegacyServiceApi.get().asFile.absolutePath)
    modelPackage.set("dev.blocks.android.api.generated.v02.model")
    packageName.set("dev.blocks.android.api.generated.v02")
    validateSpec.set(true)
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "library" to "jvm-okhttp4",
            "serializationLibrary" to "gson",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(
        mapOf(
            "apiDocs" to "false",
            "apis" to "false",
            "modelDocs" to "false",
            "models" to "",
            "modelTests" to "false",
            "supportingFiles" to "false",
        ),
    )
    dependsOn(verifyLegacyServiceApiContract)
}

android {
    namespace = "dev.blocks.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.blocks.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 24
        versionName = "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BLOCKS_API_BASE_URL", "\"https://api.blocks.onemorerabbit.co.uk\"")
        buildConfigField(
            "String",
            "BLOCKS_GOOGLE_SERVER_CLIENT_ID",
            "\"12259488358-ogmpp0cg1a8v1pbvkoivo9lgr94n0kb9.apps.googleusercontent.com\"",
        )
        buildConfigField("String", "BLOCKS_SERVICE_API_VERSION", "\"0.8\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "BLOCKS_API_BASE_URL", "\"http://blocks-api:8080\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.fragment:fragment:1.5.7")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.json:json:20250517")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

tasks.named("openApiGenerate") {
    dependsOn(verifyServiceApiContract)
}

val openApiGenerateTask = tasks.named<GenerateTask>("openApiGenerate")

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(
        openApiGenerateTask,
        GenerateTask::outputDir,
    )
    variant.sources.kotlin?.addGeneratedSourceDirectory(
        openApiGenerateLegacyV07,
        GenerateTask::outputDir,
    )
    variant.sources.kotlin?.addGeneratedSourceDirectory(
        openApiGenerateLegacyV06,
        GenerateTask::outputDir,
    )
    variant.sources.kotlin?.addGeneratedSourceDirectory(
        openApiGenerateLegacyV04,
        GenerateTask::outputDir,
    )
    variant.sources.kotlin?.addGeneratedSourceDirectory(
        openApiGenerateLegacyV02,
        GenerateTask::outputDir,
    )
}
