package it.unibo.tests.mimic

import it.unibo.stats.TestConfig
import it.unibo.stats.loadDataset
import it.unibo.tests.mimic.loaders.MimicIVAeonG
import it.unibo.tests.mimic.loaders.MimicIVNeo4J
import it.unibo.tests.mimic.loaders.MimicIVPGAge
import it.unibo.tests.mimic.loaders.MimicIVSTGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMimicIngestion {
    @Test
    fun `ingest MIMIC`() {
        TestConfig.runIngestion("mimic") { size, threads, host, controllerIPs ->
            MimicIVSTGraph(size, threads = threads, host = host, controllerIPs = controllerIPs)
        }
    }
}

fun main() {
    TestConfig.loadConfig().datasets["mimic"]!!.sizes.forEach { size ->
        println(size)
        loadDataset(MimicIVAeonG(size .toString().toLong()), "aeong", 1, 1,"mimic", size.toString())
        loadDataset(MimicIVPGAge(size.toString().toLong()), "pgage", 1, 1,"mimic", size.toString())
        loadDataset(MimicIVNeo4J(size.toString().toLong(), csv = true), "neo4j", 1, 1,"mimic", size.toString())
    }
}