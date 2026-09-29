# Phase 1 spike: answers

Sep 29, 2026 · Kotlin 2.4.20, Gradle 9.8.0, JDK 21

The spike's round trip, `comptime { 6 * 7 }` compiled to `bipush 42; ireturn`, works both in-process (`SpikeTest`) and from inside a real Kotlin daemon started by Gradle (`GradleFunctionalTest`, "round trip inside the Kotlin daemon"). Every checklist item from [plan.md](plan.md#spike-checklist-and-risks) has an answer below, each backed by a test in the repo.

## 1. Spawning the host from inside the Kotlin daemon

**Works, no surprises.** The IR extension starts the host with `ProcessBuilder`, redirects its output to files in the job directory, and reads the results back. Under Gradle, the compile runs in a spawned `KotlinCompileDaemon` (the build log shows `Options for KOTLIN DAEMON: IncrementalCompilationOptions(...)`), and the host runs as its child with no classloader or security issues.

- Evidence: `GradleFunctionalTest` "round trip inside the Kotlin daemon".
- Blocks run in the module directory, see declared env vars that are forced onto the process, and report `System.exit`, crashes and timeouts: `ResultTypesTest`, `BlockSemanticsTest`, `ErrorsTest`.

## 2. How const reads appear in IR before lowering

**As already-inlined `IrConst`s**, for same-module, other-module and Java constants alike: `ConstInliner` runs before IR generation extensions and marks each one `IrConst.wasInlined = true`.

One detail differed from the plan: **the offsets cover only the selector.** For `Int.MIN_VALUE` the constant's span is `MIN_VALUE`, not `Int.MIN_VALUE`. Splicing only that span produced `Int.(-2147483647 - 1)`, which the host rejected. The splicer now walks back over the qualifier chain (`Obj.`, `pkg.Obj.`, `Outer.Companion.`) and replaces the whole thing. Receivers with side effects become an `IrComposite(receiver, const)`, and the reference check rejects those.

- Evidence: `BlockSemanticsTest` "consts from this module, other modules and Java are spliced". It covers `Limits.MAX`, `lib.Limits.MAX`, `lib.Holder.Companion.FLAG`, `Integer.MAX_VALUE`, templates, multi-dollar strings, `Byte`/`UByte` consts, `10-Local.NEG`, and stdlib `MIN_VALUE`, NaN and infinity.

## 3. Lambda offsets

**Clean, with two findings.**

- The `IrFunctionExpression` span is the lambda **including its braces**, so the plugin pastes `{ ... }` as is.
- A label is **outside** that span: for `comptime outer@{ ... }` the span starts at `{`. The plugin recovers the label from the call text between the callee and the lambda. The same text check rejects calls through import aliases.
- One-line, multi-line and labelled lambdas, labelled returns, parenthesized and named arguments, nested `comptime` calls, and local classes and functions all extract cleanly.
- String templates: an inlined const in `$FOO` is spanned without the `$`. The splicer counts the dollars before the span, so `$FOO`, `${FOO}` and `$$FOO` in `$$"..."` strings all keep their meaning.
- CRLF files work once the file is normalized the way the compiler reads it.
- **Correction to the plan:** the compiler keeps a BOM as one character of text (`readSourceFileWithMapping` decodes UTF-8 without stripping it), so the BOM must *not* be dropped before slicing. The plan said to drop it; that is now fixed.
- Evidence: `BlockSemanticsTest` "CRLF sources, labels, nested blocks and multi-line lambdas" and "line numbers map back on CRLF files".

## 4. A changed plugin option forces a non-incremental rebuild

**Yes.** Changing `comptime { timeout = ... }` in the build script changes a plain plugin option, which is a task input. KGP logs `The input changes require a full rebuild for incremental task ':app:compileKotlin'` and passes `SourcesChanges.Unknown` to the daemon. Every file recompiles and every block reruns.

- The same test also shows the other side. Editing an undeclared data file leaves the compile task up to date (stale value, as documented). Editing an unrelated `.kt` file recompiles incrementally and does not rerun blocks in other files.
- Evidence: `GradleFunctionalTest` "a changed plugin option forces a full recompile, and IC tracks holder classes".

## 5. `listOf(vararg)` and friends in IR

**Verify and run.** Every result type is built in IR:
- `listOf`, `setOf`, `mapOf` with `Pair`, `arrayOf`, and the primitive `xxxArrayOf` functions;
- `emptyList`, `emptySet`, `emptyMap`;
- unsigned constants as signed-kind `IrConst`s with the unsigned type.

The JVM verifies and runs all of them. A 200 000-character string compiles too, split by the backend. Three 2 500-element lists in one file compile because each lives in its own holder.

- Evidence: `ResultTypesTest`, and `ErrorsTest` "a large table under the default limit compiles and runs".

## 6. Holder classes and builder functions

**Work, and incremental compilation tracks them.**
- Holders are private top-level classes with origin `COMPTIME_VALUE` (`ACC_SYNTHETIC`), one static field each. Builders are private top-level functions.
- Collections are built once (the same instance every call), and array results are fresh on every call.
- When a block is removed, incremental compilation deletes its holder's class file.
- Evidence: `ResultTypesTest` "collections are built once, arrays fresh on each evaluation" and "holder classes are synthetic and private", and `GradleFunctionalTest` (holder removal).

## Other findings

- **Typealiases** are fully expanded in IR (`IrSimpleType` has no abbreviation in 2.4.20). A project typealias to a stdlib type therefore passes the reference check and then fails to resolve on the host, which is a loud error, mapped back to the original line (`ErrorsTest` "a host compile error is mapped back to the original line"). The silent case, a project typealias whose *name* matches a different stdlib type, is not caught and is documented as a known gap.
- **Diagnostics at the same offsets are deduplicated** by the compiler's reporter. The analyzer checks receivers before callees, so an implicit outer `this` is reported instead of the member it reaches.
- **Timing:** `SpikeTest` takes about 13 s end to end on this machine: the in-process main compile, plus a host that starts a cold JVM and runs a K2 compile. That confirms the "host startup + compile time" risk is real. The phase 5 in-daemon compile is the fix.
