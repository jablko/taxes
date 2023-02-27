plugins {
  kotlin("jvm")
  application
}

repositories { mavenCentral() }

dependencies {
  implementation(rootProject)
  implementation("org.jetbrains.kotlinx:kotlinx-html:0.7.5")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
}

sourceSets {
  main {
    java {
      srcDir(".")
      exclude("**/*.gradle.kts")
    }
  }
}

tasks { named<JavaExec>("run") { workingDir = rootProject.projectDir } }

application { mainClass.set("WebsiteKt") }
