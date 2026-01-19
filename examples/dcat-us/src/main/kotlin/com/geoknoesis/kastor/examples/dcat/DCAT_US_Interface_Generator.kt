package com.geoknoesis.kastor.examples.dcat

import java.io.File
import java.io.FileWriter

/**
 * Simple interface generator for DCAT-US classes.
 * This demonstrates what the OntoMapper plugin would generate automatically.
 */
class DCAT_US_Interface_Generator {
    
    fun generateInterfaces(outputDir: File) {
        outputDir.mkdirs()
        
        // Generate Catalog interface
        generateCatalogInterface(outputDir)
        
        // Generate Dataset interface
        generateDatasetInterface(outputDir)
        
        // Generate Distribution interface
        generateDistributionInterface(outputDir)
        
        // Generate Agent interface
        generateAgentInterface(outputDir)
        
        // Generate Vocabulary
        generateVocabulary(outputDir)
    }
    
    private fun generateCatalogInterface(outputDir: File) {
        val packageDir = File(outputDir, "com/geoknoesis/kastor/examples/dcat/generated")
        packageDir.mkdirs()
        
        val content = """
package com.geoknoesis.kastor.examples.dcat.generated

/**
 * DCAT-US Catalog interface.
 * Generated from DCAT-US 3.0 SHACL shapes.
 */
interface Catalog {
    val title: String
    val description: String
    val publisher: Agent
    val issued: String
    val modified: String
    val license: String
    val language: String
    val datasets: List<Dataset>
}
""".trimIndent()
        
        FileWriter(File(packageDir, "Catalog.kt")).use { it.write(content) }
    }
    
    private fun generateDatasetInterface(outputDir: File) {
        val packageDir = File(outputDir, "com/geoknoesis/kastor/examples/dcat/generated")
        packageDir.mkdirs()
        
        val content = """
package com.geoknoesis.kastor.examples.dcat.generated

/**
 * DCAT-US Dataset interface.
 * Generated from DCAT-US 3.0 SHACL shapes.
 */
interface Dataset {
    val title: String
    val description: String
    val keywords: List<String>
    val publisher: Agent
    val issued: String
    val modified: String
    val license: String
    val rights: String
    val distributions: List<Distribution>
}
""".trimIndent()
        
        FileWriter(File(packageDir, "Dataset.kt")).use { it.write(content) }
    }
    
    private fun generateDistributionInterface(outputDir: File) {
        val packageDir = File(outputDir, "com/geoknoesis/kastor/examples/dcat/generated")
        packageDir.mkdirs()
        
        val content = """
package com.geoknoesis.kastor.examples.dcat.generated

/**
 * DCAT-US Distribution interface.
 * Generated from DCAT-US 3.0 SHACL shapes.
 */
interface Distribution {
    val title: String
    val description: String
    val accessUrl: String
    val downloadUrl: String
    val mediaType: String
    val format: String
    val byteSize: Long
}
""".trimIndent()
        
        FileWriter(File(packageDir, "Distribution.kt")).use { it.write(content) }
    }
    
    private fun generateAgentInterface(outputDir: File) {
        val packageDir = File(outputDir, "com/geoknoesis/kastor/examples/dcat/generated")
        packageDir.mkdirs()
        
        val content = """
package com.geoknoesis.kastor.examples.dcat.generated

/**
 * DCAT-US Agent interface.
 * Generated from DCAT-US 3.0 SHACL shapes.
 */
interface Agent {
    val name: String
    val homepage: String
}
""".trimIndent()
        
        FileWriter(File(packageDir, "Agent.kt")).use { it.write(content) }
    }
    
    private fun generateVocabulary(outputDir: File) {
        val packageDir = File(outputDir, "com/geoknoesis/kastor/examples/dcat/generated")
        packageDir.mkdirs()
        
        val content = """
package com.geoknoesis.kastor.examples.dcat.generated

/**
 * DCAT-US Vocabulary.
 * Generated from DCAT-US 3.0 SHACL shapes.
 */
object DCAT_US {
    const val NAMESPACE = "http://www.w3.org/ns/dcat#"
    const val PREFIX = "dcat"
    
    // Classes
    const val CATALOG = "http://www.w3.org/ns/dcat#Catalog"
    const val DATASET = "http://www.w3.org/ns/dcat#Dataset"
    const val DISTRIBUTION = "http://www.w3.org/ns/dcat#Distribution"
    const val DATA_SERVICE = "http://www.w3.org/ns/dcat#DataService"
    
    // Properties
    const val TITLE = "http://purl.org/dc/terms/title"
    const val DESCRIPTION = "http://purl.org/dc/terms/description"
    const val PUBLISHER = "http://purl.org/dc/terms/publisher"
    const val ISSUED = "http://purl.org/dc/terms/issued"
    const val MODIFIED = "http://purl.org/dc/terms/modified"
    const val LICENSE = "http://purl.org/dc/terms/license"
    const val LANGUAGE = "http://purl.org/dc/terms/language"
    const val DATASETS = "http://www.w3.org/ns/dcat#dataset"
    const val KEYWORD = "http://www.w3.org/ns/dcat#keyword"
    const val RIGHTS = "http://purl.org/dc/terms/rights"
    const val DISTRIBUTIONS = "http://www.w3.org/ns/dcat#distribution"
    const val ACCESS_URL = "http://www.w3.org/ns/dcat#accessURL"
    const val DOWNLOAD_URL = "http://www.w3.org/ns/dcat#downloadURL"
    const val MEDIA_TYPE = "http://www.w3.org/ns/dcat#mediaType"
    const val FORMAT = "http://purl.org/dc/terms/format"
    const val BYTE_SIZE = "http://www.w3.org/ns/dcat#byteSize"
}
""".trimIndent()
        
        FileWriter(File(packageDir, "DCAT_US.kt")).use { it.write(content) }
    }
}

/**
 * Main function to generate interfaces
 */
fun main() {
    val generator = DCAT_US_Interface_Generator()
    val outputDir = File("build/generated/sources/ontomapper")
    
    println("Generating DCAT-US interfaces...")
    generator.generateInterfaces(outputDir)
    println("✅ Generated interfaces in: ${outputDir.absolutePath}")
    
    // List generated files
    val generatedFiles = outputDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
    println("\nGenerated files:")
    generatedFiles.forEach { file ->
        println("  - ${file.relativeTo(outputDir)}")
    }
}









