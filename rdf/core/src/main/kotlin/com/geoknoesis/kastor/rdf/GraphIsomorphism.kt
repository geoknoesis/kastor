package com.geoknoesis.kastor.rdf

/**
 * Graph isomorphism utilities for RDF graphs with blank nodes.
 * 
 * This implementation uses the Weisfeiler-Lehman algorithm for graph isomorphism
 * testing, which is particularly effective for graphs with labeled nodes and edges.
 * 
 * The algorithm works by iteratively refining node labels based on their neighborhood
 * structure until either a stable labeling is reached or differences are detected.
 */

/**
 * Represents a node in the graph for isomorphism testing
 */
data class GraphNode(
    val id: String,
    val label: String,
    val neighbors: MutableMap<String, MutableSet<String>> = mutableMapOf()
) {
    fun addNeighbor(edgeLabel: String, neighborId: String) {
        neighbors.getOrPut(edgeLabel) { mutableSetOf() }.add(neighborId)
    }
}

/**
 * Represents an RDF graph for isomorphism testing
 */
class IsomorphismGraph {
    private val nodes = mutableMapOf<String, GraphNode>()
    var edgeCounts: Map<String, Int> = emptyMap()
    
    fun addNode(id: String, label: String): GraphNode {
        return nodes.getOrPut(id) { GraphNode(id, label) }
    }
    
    fun getNode(id: String): GraphNode? = nodes[id]
    
    fun getAllNodes(): Collection<GraphNode> = nodes.values
    
    fun addEdge(fromId: String, edgeLabel: String, toId: String) {
        val fromNode = nodes[fromId] ?: throw IllegalArgumentException("Node $fromId not found")
        fromNode.addNeighbor(edgeLabel, toId)
    }
    
    fun size(): Int = nodes.size
}

/**
 * Weisfeiler-Lehman graph isomorphism algorithm
 */
class WeisfeilerLehmanIsomorphism {
    
    /**
     * Check if two RDF graphs are isomorphic, considering blank nodes
     */
    fun areIsomorphic(graph1: RdfGraph, graph2: RdfGraph): Boolean {
        val isoGraph1 = buildIsomorphismGraph(graph1)
        val isoGraph2 = buildIsomorphismGraph(graph2)
        
        return areIsomorphic(isoGraph1, isoGraph2)
    }
    
    /**
     * Build an isomorphism graph from an RDF graph
     */
    private fun buildIsomorphismGraph(rdfGraph: RdfGraph): IsomorphismGraph {
        val isoGraph = IsomorphismGraph()
        val triples = rdfGraph.getTriples()
        
        // Count edge multiplicities
        val edgeCounts = mutableMapOf<String, Int>()
        
        // Add all nodes
        for (triple in triples) {
            val subjId = getNodeId(triple.subject)
            val objId = getNodeId(triple.obj)
            
            val subjLabel = getNodeLabel(triple.subject)
            val objLabel = getNodeLabel(triple.obj)
            
            isoGraph.addNode(subjId, subjLabel)
            isoGraph.addNode(objId, objLabel)
            
            // Count edges
            val edgeKey = "$subjId-${getNodeLabel(triple.predicate)}-$objId"
            edgeCounts[edgeKey] = (edgeCounts[edgeKey] ?: 0) + 1
        }
        
        // Add all edges with multiplicity information
        for (triple in triples) {
            val subjId = getNodeId(triple.subject)
            val objId = getNodeId(triple.obj)
            val predLabel = getNodeLabel(triple.predicate)
            
            isoGraph.addEdge(subjId, predLabel, objId)
        }
        
        // Store edge counts in the graph for later use
        isoGraph.edgeCounts = edgeCounts
        
        return isoGraph
    }
    
    /**
     * Get a unique identifier for an RDF term
     */
    private fun getNodeId(term: RdfTerm): String {
        return when (term) {
            is Iri -> "iri:${term.value}"
            is BlankNode -> "bnode:${term.id}"
            is Literal -> "literal:${term.lexical}:${term.datatype.value}"
            else -> "unknown:${term.hashCode()}"
        }
    }
    
    /**
     * Get a label for an RDF term (used for isomorphism testing)
     */
    private fun getNodeLabel(term: RdfTerm): String {
        return when (term) {
            is Iri -> "IRI"
            is BlankNode -> "BLANK" // All blank nodes get the same label
            is Literal -> when (term) {
                is LangString -> "LITERAL_LANG:${term.lang}"
                else -> "LITERAL:${term.datatype.value}"
            }
            else -> "UNKNOWN"
        }
    }
    
    /**
     * Check if two isomorphism graphs are isomorphic using Weisfeiler-Lehman
     */
    private fun areIsomorphic(graph1: IsomorphismGraph, graph2: IsomorphismGraph): Boolean {
        if (graph1.size() != graph2.size()) {
            return false
        }
        
        // Check edge count multiset first (this catches duplicate triples)
        val edgeCounts1 = graph1.edgeCounts.values.groupingBy { it }.eachCount()
        val edgeCounts2 = graph2.edgeCounts.values.groupingBy { it }.eachCount()
        if (edgeCounts1 != edgeCounts2) {
            return false
        }
        
        // Initial labeling based on node degrees and labels
        val labels1 = initializeLabels(graph1)
        val labels2 = initializeLabels(graph2)
        
        // Check if initial labeling already distinguishes the graphs
        if (!areLabelingsEquivalent(labels1, labels2)) {
            return false
        }
        
        // Iterative refinement using Weisfeiler-Lehman
        val maxIterations = graph1.size()
        for (iteration in 1..maxIterations) {
            val newLabels1 = refineLabels(graph1, labels1)
            val newLabels2 = refineLabels(graph2, labels2)
            
            if (!areLabelingsEquivalent(newLabels1, newLabels2)) {
                return false
            }
            
            // Check if labels have stabilized
            if (labels1 == newLabels1 && labels2 == newLabels2) {
                break
            }
            
            labels1.clear()
            labels1.putAll(newLabels1)
            labels2.clear()
            labels2.putAll(newLabels2)
        }
        
        return true
    }
    
    /**
     * Initialize node labels based on node properties
     */
    private fun initializeLabels(graph: IsomorphismGraph): MutableMap<String, String> {
        val labels = mutableMapOf<String, String>()
        
        for (node in graph.getAllNodes()) {
            val degree = node.neighbors.values.sumOf { it.size }
            val outDegrees = node.neighbors.mapValues { it.value.size }
            val inDegrees = mutableMapOf<String, Int>()
            
            // Calculate in-degrees
            for (otherNode in graph.getAllNodes()) {
                for ((edgeLabel, neighbors) in otherNode.neighbors) {
                    if (neighbors.contains(node.id)) {
                        inDegrees[edgeLabel] = (inDegrees[edgeLabel] ?: 0) + 1
                    }
                }
            }
            
            val label = "${node.label}:out=$outDegrees:in=$inDegrees:total=$degree"
            labels[node.id] = label
        }
        
        return labels
    }
    
    /**
     * Refine labels using neighborhood information
     */
    private fun refineLabels(
        graph: IsomorphismGraph, 
        currentLabels: Map<String, String>
    ): MutableMap<String, String> {
        val newLabels = mutableMapOf<String, String>()
        
        for (node in graph.getAllNodes()) {
            val neighborLabels = mutableListOf<String>()
            
            for ((edgeLabel, neighbors) in node.neighbors) {
                for (neighborId in neighbors) {
                    val neighborLabel = currentLabels[neighborId] ?: "UNKNOWN"
                    neighborLabels.add("$edgeLabel:$neighborLabel")
                }
            }
            
            neighborLabels.sort() // Ensure deterministic ordering
            val refinedLabel = "${currentLabels[node.id]}:neighbors=[${neighborLabels.joinToString(",")}]"
            newLabels[node.id] = refinedLabel
        }
        
        return newLabels
    }
    
    /**
     * Check if two labelings are equivalent (considering only the multiset of labels)
     */
    private fun areLabelingsEquivalent(labels1: Map<String, String>, labels2: Map<String, String>): Boolean {
        val multiset1 = labels1.values.groupingBy { it }.eachCount()
        val multiset2 = labels2.values.groupingBy { it }.eachCount()
        
        return multiset1 == multiset2
    }
}

/**
 * Extension function to check if two RDF graphs are isomorphic
 */
fun RdfGraph.isIsomorphicTo(other: RdfGraph): Boolean {
    return WeisfeilerLehmanIsomorphism().areIsomorphic(this, other)
}

/**
 * Extension function to find a mapping between blank nodes in two isomorphic graphs
 */
fun RdfGraph.findBlankNodeMapping(other: RdfGraph): Map<BlankNode, BlankNode>? {
    if (!this.isIsomorphicTo(other)) {
        return null
    }
    
    // For now, return empty mapping - full implementation would require
    // backtracking algorithm to find actual node correspondences
    return emptyMap()
}
