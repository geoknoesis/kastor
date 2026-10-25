package com.geoknoesis.kastor.ontoquality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs;
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning;
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile;
import com.geoknoesis.kastor.rdf.Rdf;
import com.geoknoesis.kastor.rdf.RdfFormat;
import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The library's factory entry points are static from Java (no {@code Companion}); objects are reached through {@code INSTANCE}. */
class JavaInteropTest {
  private static final String TURTLE =
      "@prefix ex: <http://example.org/j#> . @prefix owl: <http://www.w3.org/2002/07/owl#> . ex:A a owl:Class .";

  @Test
  void entryPointsAreStatic() {
    ShaclValidator validator = ShaclValidation.INSTANCE.validator();
    RdfGraph graph = Rdf.INSTANCE.parse(TURTLE, RdfFormat.TURTLE);
    QualityChecker checker = QualityChecker.builder(validator).addCatalog(BundledCatalogs.INSTANCE.getOWL_QUALITY()).build();
    QualityReport report = checker.check(graph, OntoQualityReasoningProfile.NONE, Duration.ofMinutes(1));
    assertFalse(report.getFindings().isEmpty());
    assertFalse(report.getTruncated());
    assertFalse(QualityChecker.allBundled(validator).check(graph).getFindings().isEmpty());
    assertTrue(OntoQualityReasoning.INSTANCE.supports(OntoQualityReasoningProfile.NONE));
    assertFalse(BundledCatalogs.INSTANCE.getAllWithOopsRegistry().isEmpty());
    QualityReport rebuilt = QualityReport.from(report.getUnderlying(), BundledCatalogs.INSTANCE.getAll());
    assertTrue(rebuilt.getFindings().size() == report.getFindings().size());
  }

  @Test
  void aNonPositiveReasonerTimeoutIsRejected() {
    QualityChecker checker = QualityChecker.allBundled(ShaclValidation.INSTANCE.validator());
    RdfGraph graph = Rdf.INSTANCE.parse(TURTLE, RdfFormat.TURTLE);
    assertThrows(IllegalArgumentException.class, () -> checker.check(graph, OntoQualityReasoningProfile.RDFS, Duration.ZERO));
  }
}
