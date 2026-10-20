package com.smartfarm360.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers the tenant interceptor and lets controllers declare {@link TenantContext} and {@link CurrentUser} parameters. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final TenantContextInterceptor tenantInterceptor;

    public WebMvcConfig(TenantContextInterceptor tenantInterceptor) { this.tenantInterceptor = tenantInterceptor; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == TenantContext.class; }
            @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest request, WebDataBinderFactory f) {
                Object context = request.getAttribute(TenantContextInterceptor.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (context == null) throw new IllegalStateException("TenantContext requested outside a /tenants/{tenantId} path");
                return context;
            }
        });
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CurrentUser.class; }
            @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest request, WebDataBinderFactory f) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof CurrentUser user) return user;
                throw new IllegalStateException("No authenticated user");
            }
        });
    }
}
