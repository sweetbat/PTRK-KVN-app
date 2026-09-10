import org.gradle.api.tasks.Copy

val bundledAar = layout.projectDirectory.file("libs/olcrtc.aar")
val olcrtcAndroidAarFile = layout.buildDirectory.file("olcrtc.aar")

val provideOlcrtcAndroidAar = tasks.register<Copy>("buildOlcrtcAndroidAar") {
    group = "build"
    description = "Copies bundled libs/olcrtc.aar into the build directory."
    from(bundledAar)
    into(layout.buildDirectory)
    rename { "olcrtc.aar" }
    onlyIf { bundledAar.asFile.exists() && bundledAar.asFile.length() > 0L }
    doFirst {
        if (!bundledAar.asFile.exists() || bundledAar.asFile.length() <= 0L) {
            throw GradleException(
                "Missing ${bundledAar.asFile.absolutePath}. Place a prebuilt olcrtc Android AAR there."
            )
        }
    }
}

configurations.maybeCreate("default")
artifacts.add("default", olcrtcAndroidAarFile) {
    builtBy(provideOlcrtcAndroidAar)
}
