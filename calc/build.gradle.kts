plugins {
  kotlin("jvm")
  application
}

repositories { mavenCentral() }

dependencies {
  implementation(rootProject)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.3")
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

application { mainClass.set("CalcKt") }
