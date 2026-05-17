package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * Integration layer that converts AST queries to existing query interfaces.
 * 
 * This allows the new DSL to work seamlessly with existing repository APIs.
 */
object SparqlQueryIntegration {
    
    /**
     * Converts a SelectQueryAst to SparqlSelect.
     */
    fun toSparqlSelect(query: SelectQueryAst): SparqlSelect {
        val sparql = SparqlRenderer.render(query)
        return SparqlSelectQuery(sparql)
    }
    
    /**
     * Converts an AskQueryAst to SparqlAsk.
     */
    fun toSparqlAsk(query: AskQueryAst): SparqlAsk {
        val sparql = SparqlRenderer.render(query)
        return SparqlAskQuery(sparql)
    }
    
    /**
     * Converts a ConstructQueryAst to SparqlConstruct.
     */
    fun toSparqlConstruct(query: ConstructQueryAst): SparqlConstruct {
        val sparql = SparqlRenderer.render(query)
        return SparqlConstructQuery(sparql)
    }
    
    /**
     * Converts a DescribeQueryAst to SparqlDescribe.
     */
    fun toSparqlDescribe(query: DescribeQueryAst): SparqlDescribe {
        val sparql = SparqlRenderer.render(query)
        return SparqlDescribeQuery(sparql)
    }
    
    /**
     * Converts an UpdateRequestAst to UpdateQuery.
     */
    fun toUpdateQuery(update: UpdateRequestAst): UpdateQuery {
        val sparql = SparqlRenderer.render(update)
        return UpdateQuery(sparql)
    }
}

/**
 * Extension functions for seamless integration.
 */
fun SelectQueryAst.toSparqlSelect(): SparqlSelect = SparqlQueryIntegration.toSparqlSelect(this)
fun AskQueryAst.toSparqlAsk(): SparqlAsk = SparqlQueryIntegration.toSparqlAsk(this)
fun ConstructQueryAst.toSparqlConstruct(): SparqlConstruct = SparqlQueryIntegration.toSparqlConstruct(this)
fun DescribeQueryAst.toSparqlDescribe(): SparqlDescribe = SparqlQueryIntegration.toSparqlDescribe(this)
fun UpdateRequestAst.toUpdateQuery(): UpdateQuery = SparqlQueryIntegration.toUpdateQuery(this)

