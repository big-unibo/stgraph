package it.unibo.tests.evaluation

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.util.logging.Logger
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

private val logger = Logger.getLogger("Download")
private const val BASE_URL = "https://big.csr.unibo.it/downloads/stgraph"

/** The downloader of downloadDataset.sh: ./gradlew downloadDataset --args="<dataset> <size or file>" */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: <dataset> <size or file>, e.g., smartbench small, smartbench medium.tar.gz, mimic x1692200.dump" }
    downloadDataset(args[0], args[1])
}

/**
 * The only downloader of the datasets, of the evaluation and of downloadDataset.sh: downloads [dataset]/[sizeOrFile] (a
 * size means <size>.tar.gz) into datasets/original/[dataset] and extracts the .tar.gz archives, which are then removed.
 * The JSON files of SmartBench are gzipped while extracted (<name>.json.gz, read as they are by its loaders, see
 * smartBenchReader): no uncompressed copy on disk. An archive is extracted in a hidden folder first, and its folders
 * (e.g., the size) are moved into datasets/original/[dataset] only once it is complete: an interrupted extraction (e.g.,
 * a full disk) leaves no folder that looks like a dataset, so the next run downloads it again
 */
fun downloadDataset(dataset: String, sizeOrFile: String) {
    val fileName = archiveName(sizeOrFile)
    val outputDir = Paths.get("datasets/original/$dataset").toAbsolutePath().normalize()
    Files.createDirectories(outputDir)
    val url = "$BASE_URL/$dataset/$fileName"
    val target = outputDir.resolve(fileName)
    val partial = outputDir.resolve("$fileName.part")
    logger.info("Downloading $url")
    val client = httpClient()
    val request = HttpRequest.newBuilder(URI(url)).GET().build()
    var attempt = 0
    while (true) {
        attempt++
        val status = try {
            client.send(request, HttpResponse.BodyHandlers.ofFile(partial)).statusCode()
        } catch (e: IOException) {
            if (attempt > RETRIES) throw e
            logger.warning("Download of $url failed (${e.message}), retrying")
            continue
        }
        if (status in 200..299) break
        Files.deleteIfExists(partial)
        // As curl --retry: only the transient errors (timeout, 429, 5xx) are retried
        if (attempt > RETRIES || (status != 408 && status != 429 && status < 500)) error("Download of $url failed: HTTP $status")
        logger.warning("Download of $url failed: HTTP $status, retrying")
    }
    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    if (fileName.endsWith(".tar.gz")) {
        logger.info("Extracting $target")
        val staging = outputDir.resolve(".$fileName.extracting")
        staging.toFile().deleteRecursively()
        extractTarGz(target, staging, gzipJson = dataset == "smartbench")
        Files.list(staging).use { it.toList() }.forEach { extracted ->
            val destination = outputDir.resolve(extracted.fileName.toString())
            destination.toFile().deleteRecursively()
            Files.move(extracted, destination, StandardCopyOption.ATOMIC_MOVE)
        }
        Files.delete(staging)
        Files.delete(target)
    }
}

private fun archiveName(sizeOrFile: String) = if (sizeOrFile.contains(".")) sizeOrFile else "$sizeOrFile.tar.gz"

/** As curl -k -fL: redirects followed, the certificate of the server not checked */
private fun httpClient(): HttpClient =
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).sslContext(insecureSslContext()).build()

/** Retries after the first attempt, as curl --retry 3 */
private const val RETRIES = 3

/**
 * An SSL context that accepts any certificate, for any host (as curl -k). An X509ExtendedTrustManager, so that JSSE
 * does not wrap it with its own check of the host name
 */
private fun insecureSslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
    val trustAll = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    init(null, arrayOf(trustAll), null)
}

/**
 * Extracts [archive] into [outputDir]; the entries that would end up outside of it are refused. With [gzipJson], the
 * .json files are written gzipped (<name>.json.gz)
 */
private fun extractTarGz(archive: Path, outputDir: Path, gzipJson: Boolean = false) {
    TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive).buffered())).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val destination = outputDir.resolve(entry.name).normalize()
            require(destination.startsWith(outputDir)) { "Entry outside of $outputDir in $archive: ${entry.name}" }
            when {
                entry.isDirectory -> Files.createDirectories(destination)
                entry.isFile -> {
                    Files.createDirectories(destination.parent)
                    if (gzipJson && entry.name.endsWith(".json")) {
                        GZIPOutputStream(Files.newOutputStream(destination.resolveSibling("${destination.fileName}.gz")), 1 shl 16).use { tar.copyTo(it) }
                    } else {
                        Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                else -> logger.warning("Skipped ${entry.name} in $archive (neither a file nor a directory)")
            }
        }
    }
}
