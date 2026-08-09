package com.gepe.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(Application.class);

    @Test
    void verifyModularity() {
        modules.verify();
    }

    @Test
    void writeDocumentationSnapshot() {
        new Documenter(modules).writeDocumentation();
    }
}
