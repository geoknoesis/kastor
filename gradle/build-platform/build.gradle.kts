// Build-internal Gradle platform. It is NOT published.
//
// It carries third-party alignment platforms and security version floors that the Kastor
// build wants on its own classpaths (compile, runtime, test, JMH, KSP processor). The root
// build adds it to every resolvable configuration that sees a Kastor project, at resolution
// time only, so it never appears in the published POMs or Gradle module metadata.
//
// Consumers do not inherit these floors. Downstream applications should apply their own
// constraints; docs/reference/release-contract.md lists the floors Kastor is tested with.
plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform(libs.jackson.bom))
    api(platform(libs.netty.bom))
    api(platform(libs.opentelemetry.bom))
    constraints {
        api(libs.guava)
        api(libs.commons.beanutils)
        api(libs.httpclient5)
        api(libs.httpcore5)
        api(libs.httpcore5.h2)
        api(libs.httpclient4)
        api(libs.apache.mime4j.core)
        api(libs.libthrift)
        api(libs.jsoup)
        api(libs.commons.lang3)
        api(libs.lz4.java)
    }
}
