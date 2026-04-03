# comptime-kt

A Kotlin Compiler Plugin that provides utilities for retrieving names and metadata of classes, properties, and functions at **compile time**.

By transforming IR (Intermediate Representation) during compilation, `comptime-kt` replaces specific calls and property accesses with constant string literals. This ensures **zero runtime overhead**, improved performance over reflection, and full compatibility with R8/ProGuard.


## Features

*   **Type-safe**: Uses Kotlin references (`::class`, `::member`) instead of hardcoded strings.
*   **Zero Runtime Cost**: Calls are replaced by string literals (e.g., `"MyClass"`) at compile time.
*   **Context Aware**: Access the name of the "current" class or method automatically using the `This` scope.
*   **Refactoring Friendly**: Renaming a class or function via your IDE will automatically update the result.
*   **K2 Compatible**: Built to support the next-generation Kotlin compiler.

## Usage

### 1. `nameOf()`
Simply wrap a class, function, variable, or property reference with `nameOf()`:

```kotlin
import dev.ujhhgtg.comptime.nameOf

class ClassName
fun functionName() {}
val propertyName = "value"

fun main() {
    println(nameOf(ClassName::class))  // "ClassName"
    println(nameOf(::functionName))    // "functionName"
    println(nameOf(::propertyName))    // "propertyName"
}
```

### 2. `This` Context
Retrieve metadata about the enclosing scope without boilerplate:

```kotlin
package dev.example

import dev.ujhhgtg.comptime.This

class MyService {
    fun processData() {
        // Full qualified names
        println(This.Class.name)   // "dev.example.MyService"
        println(This.Method.name)  // "dev.example.MyService.processData"

        // Simple names
        println(This.Class.simpleName)  // "MyService"
        println(This.Method.simpleName) // "processData"
    }
}
```

## Full Example

```kotlin
package dev.ujhhgtg.comptime.sample

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.comptime.nameOf

class ClassName
fun functionName() {}
object ObjectName

fun test() {
    val variableName = "hello"

    println("class: ${nameOf(ClassName::class)}")   // "ClassName"
    println("function: ${nameOf(::functionName)}")  // "functionName"
    println("object: ${nameOf(ObjectName)}")        // "ObjectName"
    println("variable: ${nameOf(variableName)}")    // "variableName"
}

object Logger {
    fun logInfo() {
        // Useful for automated logging tags
        val tag = This.Class.simpleName
        val method = This.Method.simpleName
        println("[$tag::$method] Logic executed.") 
        // Output: [Logger::logInfo] Logic executed.
    }
}
```

---

## Limitations

- **Const constraints**: You cannot mark a `val x = nameOf(ClassName::class)` as `const`. However, for `private val`s, the resulting bytecode is effectively identical to a constant string.
- **Argument Type**: To maintain compatibility with objects, classes, and members simultaneously, the `nameOf` stub accepts `Any`. Validation occurs at compile-time during the IR transformation.
- **Scope**: `This.Class` and `This.Method` must be called within an actual class or function scope respectively to resolve correctly.
