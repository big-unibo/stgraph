package it.unibo.tests.evaluation

import java.util.*

/** Prints a progress line of the evaluation (steps, ingestion, queries) on the standard output */
fun progress(message: String) = println("[evaluation] $message")

/** Milliseconds as seconds, e.g., "12.3 s" */
fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
