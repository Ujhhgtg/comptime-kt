package dev.ujhhgtg.comptime

class ClassName

fun functionName() {}

val propertyName: String
    get() = ""

object ObjectName

fun testNameOf() {
    fun localFunctionName() {}
    val variableName = ""

    println("class: ${nameOf(ClassName::class)}")
    println("function: ${nameOf(::functionName)}")
    println("local function: ${nameOf(::localFunctionName)}")
    println("function compat: ${nameOf(functionName())}")
    println("property: ${nameOf(::propertyName)}")
    println("property compat: ${nameOf(propertyName)}")
    println("object: ${nameOf(ObjectName::class)}")
    println("object compat: ${nameOf(ObjectName)}")
    println("variable: ${nameOf(variableName)}")
}

//fun testComptimeError() {
//    comptimeError()
//}

fun main() {
    testNameOf()
}