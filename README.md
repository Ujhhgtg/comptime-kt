# comptime-kt

`comptime { ... }` for Kotlin: run a block of Kotlin at build time and bake its result into the compiled code, like Rust's `build.rs` but inline at the call site.

```kotlin
import dev.ujhhgtg.comptime.comptime

val crc8Table: List<Int> = comptime {
    (0 until 256).map { n ->
        var c = n
        repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
        c and 0xFF
    }
}
val schema: String = comptime { java.io.File("schema.sql").readText() }
```

A Kotlin IR compiler plugin extracts each block, runs it in a separate JVM against only the JDK and kotlin-stdlib, and replaces the call with the value. Blocks may use the JDK, kotlin-stdlib, `const val`s and Java compile-time constants from anywhere, and their own declarations; anything else is a compile error at the reference.

Status: phases 1 and 2 of the [plan](docs/plan.md) are done (Kotlin/JVM, Kotlin 2.4.20, K2). See [docs/spike.md](docs/spike.md) for what the spike found. Declared `inputs`/`env` tracking (phase 3) and JDK pinning (phase 4) are next.

## Modules

| Module | What it is |
| --- | --- |
| `comptime-runtime` | The `comptime` function. Added as `compileOnly`; no call survives compilation. |
| `comptime-compiler` | The IR compiler plugin: collect, check, splice constants, write the job, run the host, build the values. |
| `comptime-host` | The standalone runner: compiles a job's blocks with `K2JVMCompiler` and runs each in its own classloader. |
| `comptime-gradle` | The Gradle plugin `dev.ujhhgtg.comptime`. |
| `protocol/` | Job-directory code shared as source by the host and the compiler plugin. |

## Using it

Nothing is published yet. To try it in another build, publish to the local test repository and point that build at it:

```sh
./gradlew publishAllPublicationsToTestRepository   # writes build/repo
```

```kotlin
// settings.gradle.kts: add maven(uri("<path-to>/comptime-kt/build/repo")) to pluginManagement and dependency repositories
plugins {
    kotlin("jvm") version "2.4.20"
    id("dev.ujhhgtg.comptime") version "0.1.0-SNAPSHOT"
}

comptime {
    timeout.set(java.time.Duration.ofSeconds(30))   // optional; default 60 s, host compile included
}
```

## Building and testing

```sh
./gradlew build
```

- `comptime-compiler` tests compile snippets in-process with the plugin and run the output: every result type, const splicing, the reference check, and every failure path.
- `comptime-gradle` tests build fixture projects with Gradle TestKit through a real Kotlin daemon, covering the round trip, a cross-module const, and incremental compilation.

Repositories resolve through Google's Maven Central mirror first, because `repo1.maven.org` rate-limits.
