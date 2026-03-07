package dev.ujhhgtg

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
