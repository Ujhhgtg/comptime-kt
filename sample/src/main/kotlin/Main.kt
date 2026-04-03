package dev.ujhhgtg.comptime.sample

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.comptime.nameOf

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

object SomeClass {
    fun printThisClass() {
        println("this class name: ${This.Class.name}")
        println("this class simple name: ${This.Class.simpleName}")
    }

    fun printThisMethod() {
        println("this method name: ${This.Method.name}")
        println("this method simple name: ${This.Method.simpleName}")
    }
}

fun testThis() {
    SomeClass.printThisClass()
    SomeClass.printThisMethod()
}

//fun testComptimeError() {
//    comptimeError()
//}

fun main() {
    testNameOf()
    testThis()
}
