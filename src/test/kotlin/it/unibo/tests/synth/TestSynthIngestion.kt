package it.unibo.tests.synth

import it.unibo.stats.TestConfig
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
import it.unibo.tests.synth.loaders.SynthDataLoader
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSynthIngestion {
    @Test
    fun `ingest Synth`() {
        TestConfig.runIngestion("synth") { size, threads, host, controllerIPs ->
            SynthDataLoader(size, threads = threads, host = host, controllerIPs = controllerIPs)
        }
    }
}