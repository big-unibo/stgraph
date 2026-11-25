package it.unibo.tests.evaluation

import it.unibo.stats.SetupConfig
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest

/** The node controllers of a stack of the scale-out: the slots nc1-nc4 of evaluation/architectures/<system>/docker-stack.yaml */
const val SCALE_OUT_MAX_NODES = 4

/** The port of the NCService of every node controller (the default of AsterixDB) */
private const val NC_SERVICE_PORT = 9090

/**
 * The cc.conf of [system] (stgraph or graphix) on the cluster of [setup], from evaluation/architectures/<system>/
 * conf-scaleout/cc.conf: the cluster controller on its host, one node controller (two data partitions) per address of
 * its controllerIPs
 */
fun scaleOutConf(system: String, setup: SetupConfig): String {
    val template = File("evaluation/architectures/$system/conf-scaleout/cc.conf").readText()
    val nodes = setup.controllerIPs.mapIndexed { i, ip ->
        """
        [nc/nc${i + 1}]
        address=$ip
        txn.log.dir=data/txnlog
        core.dump.dir=data/coredump
        ; Two I/O devices: two data partitions, as conf-free
        iodevices=data/io0,data/io1
        nc.api.port=19004
        """.trimIndent() + "\n"
    }.joinToString("\n")
    return template.replace("{nodes}", nodes).replace("{cc}", setup.host)
}

/**
 * Runs [block] on the AsterixDB cluster of [system] (stgraph or graphix) on [setup], deployed on Docker Swarm from this
 * machine (a manager of the Swarm) with evaluation/architectures/<system>/docker-stack.yaml: the cluster controller on
 * the node with the address host, a node controller on the node of each address of controllerIPs (one per machine),
 * with the cc.conf of [scaleOutConf]. The nodes are those of the Swarm with these addresses (docker node inspect).
 * Ready when its query API on host:port answers with every node controller active; removed after, waiting until its
 * ports are free (the next stack uses the same ones). No persistence: the data go with the stack
 */
fun <T> swarmDeployed(system: String, setup: SetupConfig, readyTimeoutMs: Long = 15 * 60 * 1000, block: () -> T): T {
    val stack = "$system-scaleout"
    val ncs = setup.controllerIPs
    require(ncs.size in 1..SCALE_OUT_MAX_NODES && ncs.toSet().size == ncs.size) {
        "The scale-out deploys one node controller per machine, up to $SCALE_OUT_MAX_NODES: controllerIPs $ncs"
    }
    val nodes = swarmNodes()
    fun node(address: String) = nodes[address]
        ?: error("No node of the Swarm with the address $address (nodes: ${nodes.entries.joinToString { (a, h) -> "$h $a" }})")
    val conf = File("build/scaleout/$stack.conf").absoluteFile.apply {
        parentFile.mkdirs()
        writeText(scaleOutConf(system, setup))
    }
    val env = mutableMapOf(
        "CC_NODE" to node(setup.host),
        "CC_CONF" to conf.path,
        "CC_CONF_NAME" to "$stack-cc-${sha(conf.readText())}"
    )
    ncs.forEachIndexed { i, ip ->
        env["NC${i + 1}_NODE"] = node(ip)
        env["NC${i + 1}_REPLICAS"] = "1"
    }
    val stackFile = "evaluation/architectures/$system/docker-stack.yaml"
    // A stack left by a run that did not end (e.g., killed)
    removeStack(stack, setup)
    progress("deploy | $stack: cluster controller on ${env["CC_NODE"]} (${setup.host}), node controllers on ${ncs.joinToString { "${node(it)} ($it)" }}")
    runCommand(listOf("docker", "stack", "deploy", "-c", stackFile, stack), env)
    try {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (true) {
            val state = runCatching { clusterState(setup.host, setup.port) }.getOrElse { "not answering ($it)" }
            if (state == "ACTIVE with ${ncs.size} active node controllers") break
            // A task that failed (e.g., a port already in use on its node) or was rejected (e.g., an image that cannot be
            // pulled) is not restarted (restart_policy none): the cluster would never be ready
            val failed = failedTasks(stack)
            if (failed.isNotEmpty()) {
                failed.forEach { (service, task) ->
                    progress("deploy | $stack: $task; its last output:")
                    runCatching { runCommand(listOf("docker", "service", "logs", "--raw", "--tail", "30", service)) }
                }
                error("$stack not deployed: ${failed.joinToString { it.second }}")
            }
            if (System.currentTimeMillis() > deadline) {
                runCatching { runCommand(listOf("docker", "stack", "ps", "--no-trunc", stack)) }
                error("$stack not ready after ${readyTimeoutMs / 1000} s: $state")
            }
            Thread.sleep(2000)
        }
        progress("deploy | $stack: ready, ${ncs.size} node controller(s)")
        return block()
    } finally {
        removeStack(stack, setup)
    }
}

/** The nodes of the Swarm: their hostname by their address (docker node inspect, from a manager) */
private fun swarmNodes(): Map<String, String> {
    val ids = output(listOf("docker", "node", "ls", "-q")).lines().filter { it.isNotBlank() }
    check(ids.isNotEmpty()) { "No node of the Swarm: the scale-out runs from a manager of the Swarm" }
    val format = "{{.Status.Addr}} {{.Description.Hostname}} {{with .ManagerStatus}}{{.Addr}}{{end}}"
    return nodesByAddress(output(listOf("docker", "node", "inspect", "--format", format) + ids))
}

/**
 * The hostnames by address of [inspected] (a node per line: address, hostname and, if a manager, its manager address
 * with the port). Docker gives 0.0.0.0 as the address of the leader: its manager address instead
 */
fun nodesByAddress(inspected: String): Map<String, String> =
    inspected.lines().filter { it.isNotBlank() }.associate { line ->
        val (address, hostname, manager) = line.trim().split(" ") + ""
        (if (address == "0.0.0.0") manager.substringBeforeLast(":") else address) to hostname
    }

/** The tasks of [stack] that failed or were rejected: their service and a description (node, state, error) */
private fun failedTasks(stack: String): List<Pair<String, String>> =
    runCatching { output(listOf("docker", "stack", "ps", stack, "--no-trunc", "--format", "{{.Name}}|{{.Node}}|{{.CurrentState}}|{{.Error}}")) }
        .getOrDefault("")
        .lines().map { it.split("|") }
        .filter { it.size >= 4 && (it[2].startsWith("Failed") || it[2].startsWith("Rejected")) }
        .map { (name, node, state, error) -> name.substringBeforeLast(".") to "task $name on $node: $state, $error" }

/** The state of the cluster of AsterixDB with its query API on [host]:[port], e.g., "ACTIVE with 2 active node controllers" */
private fun clusterState(host: String, port: Int): String {
    val status = JSONObject(URI("http://$host:$port/admin/cluster").toURL().readText())
    val ncs = status.optJSONArray("ncs")
    val active = (0 until (ncs?.length() ?: 0)).count { ncs!!.getJSONObject(it).optString("state") == "ACTIVE" }
    return "${status.optString("state")} with $active active node controllers"
}

/**
 * Removes [stack], if any, and waits until its services are gone and the ports of the cluster of [setup] are free on
 * every machine (the query API on the host, the NCService of each node controller): docker stack rm does not wait
 */
private fun removeStack(stack: String, setup: SetupConfig, timeoutMs: Long = 5 * 60 * 1000) {
    val stacks = output(listOf("docker", "stack", "ls", "--format", "{{.Name}}")).lines()
    if (stack in stacks) {
        progress("deploy | $stack: removing")
        runCommand(listOf("docker", "stack", "rm", stack))
    }
    val ports = listOf(setup.host to setup.port) + setup.controllerIPs.map { it to NC_SERVICE_PORT }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val services = output(listOf("docker", "service", "ls", "-q", "--filter", "label=com.docker.stack.namespace=$stack")).isNotBlank()
        val open = ports.filter { (host, port) -> listening(host, port) }
        if (!services && open.isEmpty()) return
        check(System.currentTimeMillis() < deadline) {
            "$stack not removed after ${timeoutMs / 1000} s: " + if (services) "services left" else "ports still in use: $open"
        }
        Thread.sleep(2000)
    }
}

private fun listening(host: String, port: Int) =
    runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 2000) } }.isSuccess

/** Runs [command] and returns its output; fails if it does not succeed */
private fun output(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Exit code ${process.exitValue()}: ${command.joinToString(" ")}\n$text" }
    return text
}

private fun sha(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
