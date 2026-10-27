package com.smartfarm360.platform.web;

import com.smartfarm360.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The built web app served through the real security chain, as the production image does (O2). */
class WebAppServingTest extends ApiIntegrationTest {
    private static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("sf-web");
            Files.writeString(DIR.resolve("index.html"), "<html>SmartFarm360 app</html>");
            Files.createDirectories(DIR.resolve("assets"));
            Files.writeString(DIR.resolve("assets/app-1.js"), "console.log(1)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void webDir(DynamicPropertyRegistry registry) {
        registry.add("app.web.static-dir", () -> DIR.toString().replace('\\', '/'));
    }

    @Test
    void theRootOpensTheApp() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(MockMvcRequestBuilders.get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("SmartFarm360 app")));
    }

    @Test
    void appRoutesAndFilesAreServedWithoutSigningIn() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/t/42/settings")).andExpect(status().isOk()).andExpect(content().string(containsString("SmartFarm360 app")));
        mvc.perform(MockMvcRequestBuilders.get("/assets/app-1.js")).andExpect(status().isOk()).andExpect(content().string(containsString("console.log")));
        mvc.perform(MockMvcRequestBuilders.get("/assets/app-0.js")).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
}
