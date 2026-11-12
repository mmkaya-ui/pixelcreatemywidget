package dev.afterdrop.testkit

import java.io.File
import kotlin.system.exitProcess

/**
 * A dependency-free test harness, so the core builds and tests with nothing but a Kotlin compiler.
 * A suite is a named list of tests; [runSuites] runs them and exits non-zero on any failure.
 */
class Suite(val name: String) {
    internal val tests = ArrayList<Pair<String, () -> Unit>>()

    fun test(name: String, body: () -> Unit) {
        tests += name to body
    }
}

fun suite(name: String, init: Suite.() -> Unit): Suite = Suite(name).apply(init)

fun eq(expected: Any?, actual: Any?, what: String = "") {
    if (expected != actual) {
        throw AssertionError((if (what.isEmpty()) "" else "$what: ") + "expected <$expected> but was <$actual>")
    }
}

fun yes(condition: Boolean, what: String = "condition was false") {
    if (!condition) throw AssertionError(what)
}

inline fun <reified T : Throwable> fails(what: String = "", block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("$what: expected ${T::class.simpleName} but got $t")
    }
    throw AssertionError("$what: expected ${T::class.simpleName} but nothing was thrown")
}

fun runSuites(vararg suites: Suite) {
    var passed = 0
    val failures = ArrayList<String>()
    for (s in suites) {
        for ((name, body) in s.tests) {
            try {
                body()
                passed++
            } catch (t: Throwable) {
                failures += "${s.name} > $name: ${t::class.simpleName}: ${t.message}"
            }
        }
        println("  ${s.name}: ${s.tests.size} tests")
    }
    for (f in failures) println("FAIL  $f")
    println("${passed} passed, ${failures.size} failed")
    if (failures.isNotEmpty()) exitProcess(1)
}

/** The repository's golden/ folder: the `afterdrop.golden` property, else found by walking up. */
fun goldenDir(): File {
    System.getProperty("afterdrop.golden")?.let { return File(it) }
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val candidate = File(dir, "golden")
        if (File(candidate, "valid").isDirectory) return candidate
        dir = dir.parentFile
    }
    error("golden/ folder not found; set -Dafterdrop.golden=<path>")
}
