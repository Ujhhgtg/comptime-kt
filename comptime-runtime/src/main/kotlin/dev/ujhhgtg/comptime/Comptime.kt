package dev.ujhhgtg.comptime

/**
 * Runs [block] at build time and bakes its result into the compiled code.
 *
 * The comptime compiler plugin replaces every call with the value the block produced. The function is
 * deliberately not `inline`, so the call and its lambda survive until the plugin's IR pass. If the plugin
 * isn't applied, the call throws at runtime.
 */
public fun <T> comptime(block: () -> T): T =
    error("comptime plugin not applied")
