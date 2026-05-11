# Metadata, catalog, and geometry DSLs

{% include version-banner.md %}

This guide covers optional **`TripleDsl` / `GraphDsl`** helpers for widely used catalog, metadata, VoID, GeoSPARQL, and OWL-Time assertions. They follow the same pattern as the [BFO DSL Guide](bfo-dsl-guide.md) and [PROV-O DSL Guide](prov-o-dsl-guide.md): a scoped block that appends standard triples using vocabulary IRIs from `com.geoknoesis.kastor.rdf.vocab`.

Built-in QName prefixes include **`dcat`**, **`dcterms`**, **`void`**, **`geo`**, and **`time`** (see [Vocabularies](../concepts/vocabularies.md)).

## DCAT (`dcat { }`)

Package: `com.geoknoesis.kastor.rdf.dsl` · Entry: **`dcat { }`** · Builder: `DcatTripleBuilder`

Typical links:

- `catalog dataset datasetResource` → `dcat:dataset`
- `dataset distribution distResource` → `dcat:distribution`
- `dataset inCatalog catalogResource` → `dcat:catalog`
- `service servesDataset ds` → `dcat:servesDataset`
- `distribution.downloadURL("https://…")`, `.accessURL`, `.mediaType`, `.byteSize`, `.keyword`, `.landingPage`, `.theme`, `.endpointURL`

Use `` resource `is` DCAT.Dataset `` (backticks) for `rdf:type` where needed.

## Dublin Core Terms (`dcterms { }`)

Entry: **`dcterms { }`** · Builder: `DctermsTripleBuilder`

- Literals: `.title`, `.description`, `.abstractText`, `.alternative` (optional language second argument)
- Relations: `resource creator agent`, `publisher`, `license` (resource or string URI), `dcType`, `spatial`, `temporal`, `rightsHolder`
- `issuedLexical` / `modifiedLexical` / `createdLexical` for `xsd:date` lexical strings
- `dcSubject` for `dct:subject` (name avoids confusion with RDF “subject”)

## VoID (`voidMeta { }`)

Entry: **`voidMeta { }`** · Builder: `VoidTripleBuilder`

Kotlin reserves the identifier **`void`**, so the block is named **`voidMeta`**.

- `voidSubset`, `voidRoot`, `voidVocabulary`, `voidFeature`
- Counts: `voidTriples`, `voidClasses`, `voidDistinctSubjects`, `voidProperties`, `voidDocuments`
- `sparqlEndpoint`, `dataDump`, `uriSpace`

## GeoSPARQL (`geo { }`)

Entry: **`geo { }`** · Builder: `GeoTripleBuilder`

- `feature hasGeometry geometry` · `defaultGeometry`
- `geometry.asWkt("POINT(…)")` with datatype `geo:wktLiteral` · `asGml`
- Simple topology: `sfEquals`, `sfWithin`, `sfContains`, `sfIntersects`

## OWL-Time (`time { }`)

Entry: **`time { }`** · Builder: `TimeTripleBuilder`

- Order: `before`, `after`, `during`, `inside`
- Structure: `hasBeginning`, `hasEnd`, `hasTime`
- Datatypes: `inXSDDateTimeStamp`, `inXSDDateTime`; `numericDuration` + `unitType`

## See also

- [Compact DSL Guide](compact-dsl-guide.md)
- [DCAT](https://www.w3.org/TR/vocab-dcat/), [Dublin Core Terms](https://www.dublincore.org/specifications/dublin-core/dcmi-terms/), [VoID](https://www.w3.org/TR/void/), [GeoSPARQL](https://opengeospatial.github.io/ogc-geosparql/geosparql11/spec.html), [OWL-Time](https://www.w3.org/TR/owl-time/)
