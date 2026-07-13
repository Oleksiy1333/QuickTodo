plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.oleksiy"
version = "1.0.11"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IU", "2026.1.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal plugin for Claude Code integration
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"  // 2025.3 - older versions have critical compatibility issues
        }

        changeNotes = """
            <h3>New Features</h3>
            <ul>
                <li>Added possibility to add tasks directly in "Planned for Today"</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            create("IU", "2026.1.4")
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
