package com.chessai.vision;

import java.nio.file.Path;

/** Selects a classpath or on-disk template source from system properties. */
public final class TemplateSources {
    private TemplateSources() {
    }

    public static TemplateSource create() {
        String directory = System.getProperty("chessai.templates.dir", "").trim();
        if (!directory.isEmpty()) {
            return new FileTemplateSource(Path.of(directory));
        }
        return new AwtTemplateSource();
    }
}
