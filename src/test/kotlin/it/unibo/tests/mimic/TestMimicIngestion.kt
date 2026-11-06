package it.unibo.tests.mimic

import it.unibo.stats.TestConfig
import it.unibo.stats.loadDataset
import it.unibo.tests.mimic.loaders.MimicIVAeonG
import it.unibo.tests.mimic.loaders.MimicIVGraphix
import it.unibo.tests.mimic.loaders.MimicIVNeo4J
import it.unibo.tests.mimic.loaders.MimicIVPGAge
import it.unibo.tests.mimic.loaders.MimicIVSTGraph
import it.unibo.tests.mimic.loaders.mimicLimit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** The PostgreSQL the MIMIC-IV data is read from: datasets.mimic of test_config.yml */
private fun postgres() = TestConfig.loadConfig().datasets.getValue("mimic").postgres

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMimicIngestion {
    @Test
    fun `ingest MIMIC`() {
        TestConfig.runIngestion("mimic") { size, threads, host, controllerIPs ->
            MimicIVSTGraph(size, threads = threads, host = host, controllerIPs = controllerIPs, postgres = postgres())
        }
    }
}

fun main() {
    val postgres = postgres()
    TestConfig.loadConfig().datasets["mimic"]!!.sizes.forEach { size ->
        println(size)
        loadDataset(MimicIVAeonG(mimicLimit(size.toString()), postgres = postgres), "aeong", 1, 1,"mimic", size.toString())
        loadDataset(MimicIVPGAge(mimicLimit(size.toString()), postgres = postgres), "pgage", 1, 1,"mimic", size.toString())
        loadDataset(MimicIVNeo4J(mimicLimit(size.toString()), csv = true, postgres = postgres), "neo4j", 1, 1,"mimic", size.toString())
        loadDataset(MimicIVGraphix(size.toString(), postgres = postgres), "graphix", 1, 1, "mimic", size.toString())
    }
}
