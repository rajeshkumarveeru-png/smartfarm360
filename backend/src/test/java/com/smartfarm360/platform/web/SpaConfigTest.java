package com.smartfarm360.platform.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The built web app's files and routes, as the production image serves them (O2). */
class SpaConfigTest {
    @TempDir Path dir;

    private String root() throws IOException {
        Files.writeString(dir.resolve("index.html"), "<html>app</html>");
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("assets/app-1.js"), "js");
        return dir.toString().replace('\\', '/') + "/";
    }

    private static String name(Resource r) { return r == null ? null : r.getFilename(); }

    @Test
    void theRootAndAppRoutesGetIndexHtml() throws IOException {
        String root = root();
        assertThat(name(SpaConfig.resolve(root, ""))).isEqualTo("index.html");
        assertThat(name(SpaConfig.resolve(root, "assets"))).isEqualTo("index.html");
        assertThat(name(SpaConfig.resolve(root, "t/42/settings"))).isEqualTo("index.html");
        assertThat(SpaConfig.resolve(root, "").exists()).isTrue();
    }

    @Test
    void filesAreServedAndTheApiAndMissingFilesAreNot() throws IOException {
        String root = root();
        assertThat(name(SpaConfig.resolve(root, "assets/app-1.js"))).isEqualTo("app-1.js");
        assertThat(SpaConfig.resolve(root, "assets/app-0.js")).isNull();
        assertThat(SpaConfig.resolve(root, "api/v1/anything")).isNull();
        assertThat(SpaConfig.resolve(root, "actuator/health")).isNull();
        assertThat(SpaConfig.resolve(root, "../secret")).isNull();
    }
}
