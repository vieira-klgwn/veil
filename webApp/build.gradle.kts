plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val mediapipeVersion = "0.10.14"

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "veil.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core-privacy"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("@mediapipe/tasks-vision", mediapipeVersion))
        }
    }
}

// The MediaPipe WebAssembly runtime is loaded from the site itself, never from
// a CDN, so face detection keeps working offline and no frame can leave the
// browser.
val copyMediapipeWasm by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
    from(rootProject.layout.buildDirectory.dir("js/node_modules/@mediapipe/tasks-vision/wasm"))
    into(layout.buildDirectory.dir("processedResources/js/main/wasm"))
    include("vision_wasm_internal.*", "vision_wasm_nosimd_internal.*")
}

tasks.named("jsProcessResources") {
    dependsOn(copyMediapipeWasm)
}

listOf(
    "jsBrowserProductionWebpack",
    "jsBrowserDevelopmentWebpack",
    "jsBrowserProductionRun",
    "jsBrowserDevelopmentRun",
).forEach { name ->
    tasks.matching { it.name == name }.configureEach { dependsOn(copyMediapipeWasm) }
}
