plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.oleksiy"
version = "1.0.8"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IU", "2025.3.1")
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
                <li>Build with Claude - AI-powered task automation via Claude Code CLI</li>
                <li>Task descriptions - add detailed notes to any task</li>
                <li>Copy tasks with subtasks and descriptions</li>
                <li>Recent todos popup (Ctrl+F1) for quick navigation</li>
                <li>Configurable tooltip behavior with smart truncation</li>
                <li>Auto-pause focus timer when IDE goes idle</li>
                <li>Independent task time tracking with optional hierarchy accumulation</li>
                <li>Configure new task position (top or bottom of list)</li>
            </ul>
            <h3>Improvements</h3>
            <ul>
                <li>Improved toolbar layout and actions</li>
                <li>Enhanced tooltip design with task information</li>
                <li>Renamed "Edit Time" to "Edit Focus time" for clarity</li>
            </ul>
            <h3>Bug Fixes</h3>
            <ul>
                <li>Fixed drag-and-drop task reordering not working</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
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
