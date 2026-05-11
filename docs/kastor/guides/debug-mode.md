# Debug Mode

{% include version-banner.md %}

## Overview

Kastor provides a debug mode for troubleshooting RDF operations. Debug mode enables logging for:
- **Prefix expansion**: See how QNames (e.g., `foaf:name`) are resolved to full IRIs
- **Query tracing**: See SPARQL queries with execution details (timing, result counts)

Debug output is logged using SLF4J at the `DEBUG` level, so you can control it through your logging configuration.

## Enabling Debug Mode

### Basic Usage

```kotlin
import com.geoknoesis.kastor.rdf.RdfDebug

// Enable all debug options
RdfDebug.enable {
    showPrefixExpansion = true
    showQueryTrace = true
}

// Or enable individually
RdfDebug.showPrefixExpansion = true
RdfDebug.showQueryTrace = true

// Disable debug mode
RdfDebug.disable()
```

### Configuration

```kotlin
RdfDebug.enable {
    showPrefixExpansion = true  // Log QName → IRI resolution
    showQueryTrace = true       // Log SPARQL query execution
}
```

## Prefix Expansion Debugging

When `showPrefixExpansion` is enabled, Kastor logs how QNames are resolved to full IRIs.

### Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

// Enable prefix expansion debugging
RdfDebug.enable {
    showPrefixExpansion = true
}

val repo = Rdf.memory()

repo.add {
    prefix("foaf", FOAF.namespace)
    
    val person = iri("http://example.org/person/alice")
    person - qname("foaf:name") - "Alice"
    person - qname("foaf:age") - 30
}
```

**Debug Output:**
```
DEBUG com.geoknoesis.kastor.rdf.RdfDebug - Prefix expansion: 'foaf:name' → 'http://xmlns.com/foaf/0.1/name' (prefix mappings: {foaf=http://xmlns.com/foaf/0.1/})
DEBUG com.geoknoesis.kastor.rdf.RdfDebug - Prefix expansion: 'foaf:age' → 'http://xmlns.com/foaf/0.1/age' (prefix mappings: {foaf=http://xmlns.com/foaf/0.1/})
```

### Use Cases

- **Troubleshooting QName resolution errors**: See which prefix is missing or incorrectly mapped
- **Understanding prefix mappings**: Verify how QNames are being resolved
- **Debugging DSL operations**: See what IRIs are being created from QNames

## Query Tracing

When `showQueryTrace` is enabled, Kastor logs SPARQL query execution details.

### Example

```kotlin
import com.geoknoesis.kastor.rdf.*

// Enable query tracing
RdfDebug.enable {
    showQueryTrace = true
}

val repo = Rdf.memory()

// Add some data
repo.add {
    val person = iri("http://example.org/person/alice")
    person - FOAF.name - "Alice"
    person - FOAF.age - 30
}

// Execute a query
val result = repo.select(SparqlSelect("""
    SELECT ?name ?age WHERE {
        ?person <http://xmlns.com/foaf/0.1/name> ?name .
        ?person <http://xmlns.com/foaf/0.1/age> ?age .
    }
"""))
```

**Debug Output:**
```
DEBUG com.geoknoesis.kastor.rdf.RdfDebug - Query trace: SELECT | Query: SELECT ?name ?age WHERE { ?person <http://xmlns.com/foaf/0.1/name> ?name . ?person <http://xmlns.com/foaf/0.1/age> ?age . } | Execution time: 5ms | Result count: 1
```

### Query Types

Debug mode logs all SPARQL query types:

- **SELECT**: Logs query, execution time, and result count
- **ASK**: Logs query, execution time, and boolean result (1 for true, 0 for false)
- **CONSTRUCT**: Logs query, execution time, and number of triples constructed
- **DESCRIBE**: Logs query, execution time, and number of triples described
- **UPDATE**: Logs query and execution time

### Error Logging

When queries fail, debug mode logs the error:

```
DEBUG com.geoknoesis.kastor.rdf.RdfDebug - Query error: SELECT | Query: SELECT ?name WHERE { ?person foaf:name ?name . } | Error: Failed to parse: Unknown prefix: 'foaf'
```

### Use Cases

- **Performance debugging**: See how long queries take to execute
- **Query verification**: Verify the exact SPARQL being executed
- **Result validation**: Check result counts match expectations
- **Error diagnosis**: See detailed error information for failed queries

## Logging Configuration

Debug mode uses SLF4J, so configure your logging framework to see debug output.

### Logback Configuration

**logback.xml:**
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- Enable debug logging for Kastor -->
    <logger name="com.geoknoesis.kastor.rdf" level="DEBUG"/>
    
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

### Log4j2 Configuration

**log4j2.xml:**
```xml
<Configuration>
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
    </Appenders>
    
    <Loggers>
        <!-- Enable debug logging for Kastor -->
        <Logger name="com.geoknoesis.kastor.rdf" level="DEBUG"/>
        
        <Root level="INFO">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

### Simple Logger (SLF4J Simple)

If you're using SLF4J Simple (default in many test environments), set the system property:

```kotlin
System.setProperty("org.slf4j.simpleLogger.log.com.geoknoesis.kastor.rdf", "DEBUG")
```

## Best Practices

### 1. Enable Only What You Need

Debug logging can be verbose. Enable only the features you need:

```kotlin
// Only enable query tracing
RdfDebug.enable {
    showQueryTrace = true
}
```

### 2. Use in Development/Testing

Debug mode is primarily for development and testing. Consider disabling it in production:

```kotlin
// In production
if (System.getProperty("kastor.debug") == "true") {
    RdfDebug.enable {
        showQueryTrace = true
    }
}
```

### 3. Combine with Logging Levels

Use logging levels to control verbosity:

```kotlin
// Enable debug mode
RdfDebug.enable {
    showQueryTrace = true
}

// But only log at INFO level in production
// (Debug logs won't appear unless logger is set to DEBUG)
```

### 4. Performance Considerations

Debug mode adds minimal overhead:
- **Prefix expansion**: Only logs when QNames are resolved (negligible overhead)
- **Query tracing**: Adds timing measurement (minimal overhead, ~1-2ms per query)

For production use, consider:
- Disabling debug mode entirely
- Using conditional compilation or feature flags
- Using logging levels to control output

## Examples

### Complete Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

fun main() {
    // Enable debug mode
    RdfDebug.enable {
        showPrefixExpansion = true
        showQueryTrace = true
    }
    
    val repo = Rdf.memory()
    
    // Add data with QNames
    repo.add {
        prefix("foaf", FOAF.namespace)
        
        val person = iri("http://example.org/person/alice")
        person - qname("foaf:name") - "Alice"
        person - qname("foaf:age") - 30
    }
    
    // Query the data
    val result = repo.select(SparqlSelect("""
        SELECT ?name ?age WHERE {
            ?person <http://xmlns.com/foaf/0.1/name> ?name .
            ?person <http://xmlns.com/foaf/0.1/age> ?age .
        }
    """))
    
    result.forEach { binding ->
        println("Name: ${binding.get("name")}, Age: ${binding.get("age")}")
    }
    
    // Disable debug mode
    RdfDebug.disable()
}
```

## Related Documentation

- [QName Resolution](../reference/dsl.md#qname-resolution) - How QNames are resolved
- [SPARQL Queries](../api/core-api.md#sparql-queries) - SPARQL query API
- [Error Handling](error-handling.md) - Error handling and debugging

