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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

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
    // As curl -k -fL --retry 3 in downloadDataset.sh: the certificate of the server is not checked
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).sslContext(insecureSslContext()).build()
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
        extractTarGz(target, outputDir)
        Files.delete(target)
    }
}

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
