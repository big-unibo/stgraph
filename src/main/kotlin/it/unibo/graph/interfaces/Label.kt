package it.unibo.graph.interfaces

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileWriter
import java.io.InputStream

open class LabelString(override val ordinal: Int, override val name: String) : Label {

    override fun toString(): String = name

    // Value-based equality: two LabelStrings are equal if name and ordinal match
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LabelString) return false
        return ordinal == other.ordinal && name == other.name
    }

    override fun hashCode(): Int = ordinal
}

interface Label {
    val ordinal: Int
    val name: String

    companion object {
        val path = "src/main/resources/labels.yaml"
        private val stringToId = mutableMapOf<String, Int>()
        val entries = mutableMapOf<Int, Label>()
        private val yaml = Yaml()
        private val yamlFile = File(path)

        init {
            loadYamlLabels()
        }

        private fun register(labelName: String, label: Label) {
            stringToId[labelName] = label.ordinal
            entries[label.ordinal] = label
        }

        private fun loadYamlLabels() {
            val stream: InputStream? =
                if (yamlFile.exists()) yamlFile.inputStream()
                else Label::class.java.getResourceAsStream(path)

            if (stream != null) {
                val data = yaml.load<Map<String, List<String>>>(stream)
                data["labels"]?.forEach { labelName ->
                    register(labelName, LabelString(labelName.hashCode(), labelName))
                }
            }
        }

        fun encode(labelName: String): Int {
            stringToId[labelName]?.let { return it }
            val id = labelName.hashCode()
            val label = LabelString(id, labelName)
            register(labelName, label)
            appendLabel(labelName)
            return id
        }

        fun decode(id: Int): Label? = entries[id]

        private fun appendLabel(labelName: String) {
            val isNewFile = !yamlFile.exists()
            FileWriter(yamlFile, true).use { writer ->
                if (isNewFile) writer.write("labels:\n")
                writer.write("  - \"$labelName\"\n")
            }
        }
    }
}

fun labelFromString(labelName: String): Label = Label.decode(Label.encode(labelName))!!