package dev.ujhhgtg.comptime

@Suppress("unused")
fun nameOf(target: Any?): String = error("comptime-kt's compiler plugin is not applied.")

//fun comptimeError(): Nothing = error("comptime-kt's compiler plugin is not applied.")

object This {
    object Class {
        val name: String
            get() = error("comptime-kt's compiler plugin is not applied.")

        val simpleName: String
            get() = error("comptime-kt's compiler plugin is not applied.")
    }

    object Method {
        val name: String
            get() = error("comptime-kt's compiler plugin is not applied.")

        val simpleName: String
            get() = error("comptime-kt's compiler plugin is not applied.")
    }
}
