# nameof-kt

A Kotlin Compiler Plugin that provides a `nameof()` function to retrieve the names of classes, properties, and functions at **compile time**.

Unlike reflection-based solutions, `nameof-kt` transforms calls into constant strings during the IR (Intermediate Representation) phase, ensuring zero runtime overhead and full compatibility with R8/ProGuard.


## Features

* **Type-safe:** Uses Kotlin references (`::class`, `::member`) instead of hardcoded strings.
* **Zero Runtime Cost:** The function call is replaced by a string literal (e.g., `"MyClass"`) at compile time.
* **Refactoring Friendly:** Renaming a class or function via your IDE will automatically update the `nameof` result.
* **K2 Compatible:** Built to support the next-generation Kotlin compiler.


## Usage

Simply wrap a class, function, or property reference with `nameof()`:

```kotlin
class ClassName

fun functionName() {}

val propertyName: String
    get() = ""

object ObjectName

fun main() {
    fun localFunctionName() {}

    println("class: ${nameof(ClassName::class)}")
    println("function: ${nameof(::functionName)}")
    println("local function: ${nameof(::localFunctionName)}")
    println("property: ${nameof(::propertyName)}")
    println("object: ${nameof(ObjectName::class)}")
    println("object compat: ${nameof(ObjectName)}")
}
```
