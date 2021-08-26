plugins {
  kotlin("jvm") version "1.5.30"
  antlr
  id("com.diffplug.spotless") version "5.14.3"
}

repositories { mavenCentral() }

dependencies { antlr("org.antlr:antlr4:4.9.2") }

sourceSets {
  main {
    java {
      srcDir("src")
      srcDir(tasks.generateGrammarSource)
    }
    antlr {
      // srcDir("src") // error(7):  cannot find or open file: ../../Form.g4
      setSrcDirs(listOf("src"))
    }
  }
}

tasks {
  generateGrammarSource {
    arguments.add("-no-listener")
    arguments.add("-visitor")
  }
}

spotless {
  java {
    target("**/*.java")
    googleJavaFormat()
  }
  kotlin {
    target("**/*.kt")
    ktfmt()
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    ktfmt()
  }
  antlr4 { antlr4Formatter() }
}
