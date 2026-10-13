import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

plugins {
    id("net.fabricmc.fabric-loom")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.160.7+26.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

val windowsProject = rootProject.file(
    "smtcify-windows/smtcify-windows.csproj"
)

val helperPublishDirectory =
    layout.buildDirectory.dir("windows-helper/publish")

val helperExecutable =
    helperPublishDirectory.map { it.file("smtcify-windows.exe") }

val publishSmtcifyWindowsHelper =
    tasks.register<Exec>("publishSmtcifyWindowsHelper") {
        inputs.file(windowsProject)
        outputs.file(helperExecutable)

        commandLine(
            "dotnet",
            "publish",
            windowsProject.absolutePath,
            "--configuration", "Release",
            "--runtime", "win-x64",
            "--self-contained", "true",
            "-p:PublishSingleFile=true",
            "-p:IncludeNativeLibrariesForSelfExtract=true",
            "--output", helperPublishDirectory.get().asFile.absolutePath
        )
    }

val helperResourceDirectory =
    layout.buildDirectory.dir("generated/smtcify-windows")

val compressedHelper =
    helperResourceDirectory.map { it.file("smtcify-windows.exe.gz") }

val helperHash =
    helperResourceDirectory.map { it.file("smtcify-windows.exe.sha256") }

val prepareSmtcifyWindowsHelper =
    tasks.register("prepareSmtcifyWindowsHelper") {
        dependsOn(publishSmtcifyWindowsHelper)
        inputs.file(helperExecutable)
        outputs.file(compressedHelper)
        outputs.file(helperHash)

        doLast {
            val executable = helperExecutable.get().asFile

            if (!executable.isFile) {
                throw GradleException(
                    "No se generó el auxiliar de Windows: $executable"
                )
            }

            val compressedFile = compressedHelper.get().asFile
            val hashFile = helperHash.get().asFile

            compressedFile.parentFile.mkdirs()

            GZIPOutputStream(
                compressedFile.outputStream().buffered()
            ).use { gzip ->
                executable.inputStream().buffered().use { input ->
                    input.copyTo(gzip)
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")

            executable.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)

                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }

            val hash = digest.digest().joinToString("") {
                "%02x".format(it)
            }

            hashFile.writeText("$hash\n")
        }
    }

tasks.processResources {
    dependsOn(prepareSmtcifyWindowsHelper)

    from(compressedHelper) {
        into("smtcify/windows")
    }

    from(helperHash) {
        into("smtcify/windows")
    }

    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
