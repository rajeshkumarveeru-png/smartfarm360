package com.smartfarm360.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.File;

/**
 * Serves the built web app from {@code app.web.static-dir} at the same address as the API (SaaS operations design, O2).
 * Any path that is not a file and not under {@code /api} gets {@code index.html}, so the app's own routes work on reload.
 * With no directory configured (development), nothing is served and Vite serves the web app.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {
    private final String dir;

    public SpaConfig(@Value("${app.web.static-dir:}") String dir) { this.dir = dir == null ? "" : dir.trim(); }

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        // The resource handler never sees an empty path, so the root is forwarded to the app explicitly
        if (!dir.isEmpty()) registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        if (dir.isEmpty()) return;
        String root = dir.endsWith("/") ? dir : dir + "/";
        registry.addResourceHandler("/**").addResourceLocations("file:" + root).resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String path, @NonNull Resource location) {
                        return resolve(root, path);
                    }
                });
    }

    /**
     * The file for a request path, {@code index.html} for an app route (including the root, which is a directory), or
     * null for the API, the actuator and a missing file such as an old asset.
     */
    static Resource resolve(String root, String path) {
        if (path.startsWith("api/") || path.startsWith("actuator") || path.contains("..")) return null;
        File file = new File(root, path);
        if (!path.isEmpty() && file.isFile() && file.canRead()) return new FileSystemResource(file);
        return path.contains(".") ? null : new FileSystemResource(root + "index.html");
    }
}
