package it.unibo.tests.systems

/**
 * The query template src/test/resources/queries/[dataset]/[system]/[queryId].txt (from the classpath), with every
 * {KEY} of [parameters] replaced by its value
 */
fun queryTemplate(dataset: String, system: String, queryId: String, parameters: Map<String, Any> = emptyMap()): String {
    val path = "queries/$dataset/$system/$queryId.txt"
    val template = Thread.currentThread().contextClassLoader.getResource(path)?.readText()?.trim()
        ?: error("Query not found in the classpath: $path")
    return parameters.entries.fold(template) { text, (key, value) -> text.replace("{$key}", value.toString()) }
}
