import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tasty.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "dev.tasty.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Version freshness checks depend on what was released upstream, not on this code,
        // so they would turn CI red without any change here. Version bumps are done by hand.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    jvmToolchain(21)
}

// Rust core (rust/tasty-mobile-core): cargo-ndk builds the shared library for each ABI and
// uniffi-bindgen generates the Kotlin bindings from it. Rust is always built in release mode
// because a debug build of the terminal is too slow to use.

/** Builds the Rust core for Android with cargo-ndk. */
abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val rustDir: DirectoryProperty

    @get:Internal abstract val ndkDir: DirectoryProperty

    @get:Input abstract val abis: ListProperty<String>

    @get:Input abstract val minSdk: org.gradle.api.provider.Property<Int>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = rustDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            val targets = abis.get().flatMap { listOf("-t", it) }
            commandLine(
                listOf("cargo", "ndk") + targets +
                    listOf("-P", minSdk.get().toString(), "-o", out.absolutePath) +
                    listOf("build", "--release", "--locked", "-p", "tasty-mobile-core"),
            )
        }
    }
}

/** Generates the Kotlin bindings from a built library with the workspace's uniffi-bindgen. */
abstract class UniffiBindgen : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:Internal abstract val rustDir: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = rustDir.get().asFile
            commandLine(
                "cargo",
                "run",
                "--release",
                "--locked",
                "-q",
                "-p",
                "uniffi-bindgen",
                "--",
                "generate",
                "--library",
                library.get().asFile.absolutePath,
                "--language",
                "kotlin",
                "--no-format",
                "--out-dir",
                out.absolutePath,
            )
        }
    }
}

/**
 * Copies the license notice (U10) from the repo root into a generated assets folder, byte for byte,
 * so the APK carries the committed file. scripts/notices.sh writes and checks that file.
 */
abstract class CopyNotice : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        notice.get().asFile.copyTo(out.resolve(NOTICE_ASSET))
    }

    companion object {
        const val NOTICE_ASSET = "THIRD_PARTY_NOTICES.txt"
    }
}

val rustWorkspace = rootProject.layout.projectDirectory.dir("rust")
// Pass -Ptasty.rustAbis=x86_64 to build a single ABI while iterating on an emulator.
val rustAbis =
    providers
        .gradleProperty("tasty.rustAbis")
        .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
        .orElse(listOf("arm64-v8a", "x86_64"))

val cargoNdkBuild =
    tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        sources.from(
            fileTree(rustWorkspace) {
                include("Cargo.toml", "Cargo.lock", "*/Cargo.toml", "*/src/**", "*/uniffi.toml")
            },
        )
        rustDir.set(rustWorkspace)
        ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
        abis.set(rustAbis)
        minSdk.set(29)
        outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    }

val uniffiBindgen =
    tasks.register<UniffiBindgen>("uniffiBindgen") {
        library.set(
            cargoNdkBuild.flatMap { build ->
                build.outputDir.zip(build.abis) { dir, abis -> dir.file("${abis.first()}/libtasty_mobile_core.so") }
            },
        )
        rustDir.set(rustWorkspace)
        outputDir.set(layout.buildDirectory.dir("generated/source/uniffi"))
    }

val copyNotice =
    tasks.register<CopyNotice>("copyNotice") {
        notice.set(rootProject.layout.projectDirectory.file(CopyNotice.NOTICE_ASSET))
        outputDir.set(layout.buildDirectory.dir("generated/assets/notice"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyNotice, CopyNotice::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgen::outputDir)
    }
}

// NoticeTest reads these repo root files, so a change to either reruns the unit tests instead of
// taking their result from the build cache.
tasks.withType<Test>().configureEach {
    inputs
        .file(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.txt"))
        .withPropertyName("noticeFile")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs
        .file(rootProject.layout.projectDirectory.file("LICENSE"))
        .withPropertyName("licenseFile")
        .withPathSensitivity(PathSensitivity.NONE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // UniFFI's Kotlin bindings load the Rust library through JNA.
    implementation(libs.jna) {
        artifact { type = "aar" }
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
