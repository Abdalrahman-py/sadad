import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: the Ledger rules and the wire format of the Phase 2 `api`. No Android, no
// org.json. Built on JDK 21 but emits Java 11 bytecode, because :app compiles for Java 11.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(21)
  compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

java { targetCompatibility = JavaVersion.VERSION_11 }

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

// The tests read the shared contract (fixtures and openapi.json) straight from the repository.
val contract = rootProject.layout.projectDirectory.dir("contract")
val fixtures = contract.dir("fixtures")

tasks.test {
  inputs.dir(contract).withPropertyName("contract").withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("sadad.fixtures", fixtures.asFile.absolutePath)
}
