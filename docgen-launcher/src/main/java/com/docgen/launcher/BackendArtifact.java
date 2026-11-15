package com.docgen.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Sanity check that a jar is the DocGen API: a Spring Boot jar whose
 * Start-Class is the API application. This catches wrong or corrupted files
 * early; it is not a signature check and grants the jar no extra trust.
 */
final class BackendArtifact {
    static final String START_CLASS = "com.docgen.api.DocGenApiApplication";

    private BackendArtifact() {
    }

    /** Returns {@code null} when the jar looks like the DocGen API, otherwise a user-facing reason. */
    static String problem(Path jar) {
        if (jar == null) {
            return "não encontrado.";
        }
        if (!Files.isRegularFile(jar)) {
            return "não é um arquivo.";
        }
        if (!Files.isReadable(jar)) {
            return "sem permissão de leitura.";
        }
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            Manifest manifest = file.getManifest();
            String startClass = manifest == null ? null : manifest.getMainAttributes().getValue("Start-Class");
            if (!START_CLASS.equals(startClass)) {
                return "o arquivo não é o backend do DocGen.";
            }
            Attributes attributes = manifest.getMainAttributes();
            return attributes.getValue(Attributes.Name.MAIN_CLASS) == null
                    ? "o arquivo não é executável." : null;
        } catch (IOException | SecurityException corrupted) {
            return "o arquivo está corrompido ou não é um JAR.";
        }
    }
}
