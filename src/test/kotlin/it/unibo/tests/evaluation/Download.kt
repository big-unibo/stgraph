package it.unibo.tests.evaluation

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

private val logger = Logger.getLogger("Download")
private const val BASE_URL = "https://big.csr.unibo.it/downloads/stgraph"

/**
 * As downloadDataset.sh, without bash (e.g., on Windows): downloads [dataset]/[sizeOrFile] (a size means
 * <size>.tar.gz) into datasets/original/[dataset] and extracts the .tar.gz archives, which are then removed
 */
fun downloadDataset(dataset: String, sizeOrFile: String) {
    val fileName = if (sizeOrFile.contains(".")) sizeOrFile else "$sizeOrFile.tar.gz"
    val outputDir = Paths.get("datasets/original/$dataset").toAbsolutePath().normalize()
    Files.createDirectories(outputDir)
    val url = "$BASE_URL/$dataset/$fileName"
    val target = outputDir.resolve(fileName)
    val partial = outputDir.resolve("$fileName.part")
    logger.info("Downloading $url")
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val response = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(partial))
    if (response.statusCode() !in 200..299) {
        Files.deleteIfExists(partial)
        error("Download of $url failed: HTTP ${response.statusCode()}")
    }
    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    if (fileName.endsWith(".tar.gz")) {
        logger.info("Extracting $target")
        extractTarGz(target, outputDir)
        Files.delete(target)
    }
}

/** Extracts [archive] into [outputDir]; the entries that would end up outside of it are refused */
private fun extractTarGz(archive: Path, outputDir: Path) {
    TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive).buffered())).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val destination = outputDir.resolve(entry.name).normalize()
            require(destination.startsWith(outputDir)) { "Entry outside of $outputDir in $archive: ${entry.name}" }
            when {
                entry.isDirectory -> Files.createDirectories(destination)
                entry.isFile -> {
                    Files.createDirectories(destination.parent)
                    Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                else -> logger.warning("Skipped ${entry.name} in $archive (neither a file nor a directory)")
            }
        }
    }
}
