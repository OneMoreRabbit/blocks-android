import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

@CacheableTask
abstract class VerifyContractArtifacts : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractDirectory: DirectoryProperty

    @TaskAction
    fun verifyArtifacts() {
        val directory = contractDirectory.get()
        val entries = directory.file("SHA256SUMS").asFile.readLines().filter(String::isNotBlank)
        check(entries.size == 10) { "Expected 10 Service API contract artifacts, got ${entries.size}" }
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
val serviceApiSpec = serviceApiContractDir.file("blocks-service-api-openapi-v0_2.json")
val generatedServiceApi = layout.buildDirectory.dir("generated/openapi")

val verifyServiceApiContract by tasks.registering(VerifyContractArtifacts::class) {
    group = "verification"
    description = "Verifies the Atlas-delivered Service API 0.2 artifacts."
    contractDirectory.set(serviceApiContractDir)
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(serviceApiSpec.asFile.absolutePath)
    outputDir.set(generatedServiceApi.get().asFile.absolutePath)
    modelPackage.set("dev.blocks.android.api.generated.model")
    packageName.set("dev.blocks.android.api.generated")
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

android {
    namespace = "dev.blocks.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.blocks.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BLOCKS_API_BASE_URL", "\"https://api.blocks.onemorerabbit.co.uk\"")
        buildConfigField(
            "String",
            "BLOCKS_GOOGLE_SERVER_CLIENT_ID",
            "\"12259488358-ogmpp0cg1a8v1pbvkoivo9lgr94n0kb9.apps.googleusercontent.com\"",
        )
        buildConfigField("String", "BLOCKS_SERVICE_API_VERSION", "\"0.2\"")
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
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.json:json:20250517")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
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
}
