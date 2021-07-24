plugins {
  kotlin("jvm") version "1.5.21"
  antlr
  application
  id("com.diffplug.spotless") version "5.14.2"
}

repositories { mavenCentral() }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")
  antlr("org.antlr:antlr4:4.9.2")
}

sourceSets {
  main {
    java {
      srcDir(".")
      exclude("**/*.gradle.kts")
      // exclude(buildDir)
      exclude("build/**")
      srcDir(tasks.generateGrammarSource)
    }
    antlr {
      // srcDir(".") // error(7):  cannot find or open file: ../../../Form.g4
      setSrcDirs(listOf("."))
    }
  }
}

tasks {
  generateGrammarSource {
    arguments.add("-no-listener")
    arguments.add("-visitor")
  }
}

application { mainClass.set("CalcKt") }

spotless {
  java {
    googleJavaFormat()
    // targetExclude(tasks.generateGrammarSource)
    // targetExclude(buildDir)
    targetExclude("build/**")
  }
  kotlin { ktfmt() }
  kotlinGradle { ktfmt() }
  antlr4 { antlr4Formatter() }
}
