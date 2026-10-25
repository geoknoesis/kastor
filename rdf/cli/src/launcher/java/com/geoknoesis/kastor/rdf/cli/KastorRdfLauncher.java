package com.geoknoesis.kastor.rdf.cli;

import java.lang.reflect.InvocationTargetException;

/**
 * The main class of the {@code kastor-rdf} start scripts: refuses a Java runtime older than the one the CLI is
 * compiled for with a one-line message and exit status 3, instead of an {@code UnsupportedClassVersionError} stack
 * trace with status 1 (the status the CLI reserves for usage errors), then runs {@code KastorRdfCli.main}.
 *
 * <p>This class is compiled for Java 8 so that every runtime a user is likely to have can load it and print the
 * message. It reaches the CLI by reflection only: nothing compiled for a newer release is linked before the check.
 */
public final class KastorRdfLauncher {
    /** The Java feature release the CLI is compiled for. */
    static final int REQUIRED_JAVA_VERSION = 21;

    /** The exit status of a runtime failure, as the CLI documents it. */
    static final int EXIT_RUNTIME_ERROR = 3;

    private KastorRdfLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        String problem = javaVersionProblem(System.getProperty("java.specification.version"), System.getProperty("java.home"));
        if (problem != null) {
            System.err.println(problem);
            System.exit(EXIT_RUNTIME_ERROR);
        }
        try {
            Class.forName("com.geoknoesis.kastor.rdf.cli.KastorRdfCli").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * The feature release of a {@code java.specification.version} value ({@code 1.8} is 8, {@code 17} is 17), or -1
     * when it cannot be read.
     */
    static int featureRelease(String specificationVersion) {
        if (specificationVersion == null) return -1;
        String version = specificationVersion.startsWith("1.") ? specificationVersion.substring(2) : specificationVersion;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return -1;
        try {
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Why the runtime with {@code specificationVersion} at {@code javaHome} cannot run the CLI, or null when it can (or
     * when its version cannot be read: the JVM then reports a mismatch itself).
     */
    static String javaVersionProblem(String specificationVersion, String javaHome) {
        int release = featureRelease(specificationVersion);
        if (release < 0 || release >= REQUIRED_JAVA_VERSION) return null;
        return "kastor-rdf: error: Java " + REQUIRED_JAVA_VERSION + " or later is required, but this is Java " + specificationVersion
            + (javaHome == null ? "" : " (" + javaHome + ")")
            + "; set JAVA_HOME to a Java " + REQUIRED_JAVA_VERSION + " (or later) installation, or put its java on the PATH";
    }
}
