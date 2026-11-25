package it.unibo.tests.evaluation

import it.unibo.stats.SetupConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cc.conf of the scale-out (scaleOutConf), written in build/scaleout/test-<system>.conf to start it by hand */
class ScaleOutConfTest {
    private val setup = SetupConfig(host = "172.31.250.10", port = 19002, controllerIPs = listOf("172.31.250.11", "172.31.250.12"))

    @Test
    fun ccConf() {
        for (system in listOf("stgraph", "graphix")) {
            val conf = scaleOutConf(system, setup)
            File("build/scaleout/test-$system.conf").apply { parentFile.mkdirs() }.writeText(conf)
            assertFalse("{nodes}" in conf || "{cc}" in conf, "$system: placeholders left")
            assertEquals(listOf("nc1", "nc2"), Regex("""^\[nc/(\w+)]""", RegexOption.MULTILINE).findAll(conf).map { it.groupValues[1] }.toList())
            assertEquals(setup.controllerIPs, Regex("""^address=(\S+)""", RegexOption.MULTILINE).findAll(conf).map { it.groupValues[1] }.toList())
            assertTrue(Regex("""^address = 172\.31\.250\.10$""", RegexOption.MULTILINE).containsMatchIn(conf), "$system: cluster controller")
            assertEquals(system == "graphix", "GraphixQueryTranslatorExtension" in conf)
        }
    }

    /** Docker gives 0.0.0.0 as the address of the leader (a worker has no manager address) */
    @Test
    fun nodes() {
        val expected = mapOf("192.168.30.102" to "node2", "192.168.30.103" to "node3", "192.168.30.104" to "node4")
        for (leader in listOf("0.0.0.0", "192.168.30.102")) {
            assertEquals(expected, nodesByAddress("$leader node2 192.168.30.102:2377\n192.168.30.103 node3 \n192.168.30.104 node4 192.168.30.104:2377\n"))
        }
    }
}
