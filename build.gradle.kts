plugins {
    id("com.android.application") version "8.13.2" apply false
    // AGP 8.13.2 bundles R8 8.13.19, whose supported ceiling is Kotlin 2.3.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
