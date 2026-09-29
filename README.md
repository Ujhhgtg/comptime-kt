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

Status: all five phases of the [plan](docs/plan.md) are implemented (Kotlin/JVM and Android, Kotlin 2.4.20, K2). See [docs/spike.md](docs/spike.md) for what the spike found, and the plan's implementation notes for what building it taught us.

## Modules

| Module | What it is |
| --- | --- |
| `comptime-runtime` | The `comptime` function. Added as `compileOnly`; no call survives compilation. |
| `comptime-compiler` | The compiler plugin. A FIR checker for block shape and result type; an IR pass that checks references, splices constants, compiles the blocks in-process, runs the host, and builds the values (split across helper methods when large), with a result cache. |
| `comptime-host` | The standalone runner: runs each block in its own classloader on the chosen JDK (and compiles them, when the plugin couldn't). |
| `comptime-gradle` | The Gradle plugin `dev.ujhhgtg.comptime`. |
| `protocol/` | Code shared as source by the host and the compiler plugin: the job layout, JSON, and the block compiler. |

## Using it

Nothing is published yet. To try it in another build, publish to the local test repository and point that build at it:

```sh
./gradlew publishAllPublicationsToTestRepository   # writes build/repo
```

```kotlin
// settings.gradle.kts: add maven(uri("<path-to>/comptime-kt/build/repo")) to pluginManagement and dependency repositories
import java.time.Duration   // `java.time...` inline would resolve `java` to the Java extension

plugins {
    kotlin("jvm") version "2.4.20"
    id("dev.ujhhgtg.comptime") version "0.1.0-SNAPSHOT"
}

comptime {
    inputs.from("data/", "schema.sql")                // files blocks read; a change rebakes the module
    env.add("BUILD_FLAVOR")                           // env vars blocks read; forced onto the host with their current values
    jdk.set(JavaLanguageVersion.of(21))               // optional; default: the compile task's JDK
    timeout.set(Duration.ofSeconds(30))               // optional; default 60 s
    cache.set(false)                                  // optional; default true (results reused across builds until `clean`)
}
```

A block's value is only as fresh as what the build knows about: declare what it reads. Output from successful blocks shows with `--info`.

## Building and testing

```sh
./gradlew build
```

- `comptime-compiler` tests compile snippets in-process with the plugin and run the output: every result type, const splicing, the reference and FIR checks, large values, the result cache, and every failure path.
- `comptime-gradle` tests build fixture projects with Gradle TestKit through a real Kotlin daemon: the round trip, a cross-module const, incremental compilation, declared inputs and env vars, the cache, JDK pinning, and an Android library.
  - JDK pinning needs JDK 17 and 21 where Gradle's toolchain detection finds them (e.g. `/usr/lib/jvm`).
  - The Android test needs an SDK with platform 36 (`ANDROID_HOME`, or `/opt/android-sdk`); it's skipped otherwise.

Repositories resolve through Google's Maven Central mirror first, because `repo1.maven.org` rate-limits.
