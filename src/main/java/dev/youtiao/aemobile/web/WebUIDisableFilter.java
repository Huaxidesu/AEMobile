package dev.youtiao.aemobile.web;

import dev.youtiao.aemobile.AEMobile;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * web.enableWebUI=false 时拦截浏览器侧路径，只放行手机 App 依赖的 API。
 */
@Component
public class WebUIDisableFilter implements Filter {

    private static final String[] API_PREFIXES = {"/AE2/", "/api/"};

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (AEMobile.useWebUI) {
            chain.doFilter(request, response);
            return;
        }
        String path = ((HttpServletRequest) request).getRequestURI();
        if (path.equals("/AEMobileTest")) {
            chain.doFilter(request, response);
            return;
        }
        for (String prefix : API_PREFIXES) {
            if (path.startsWith(prefix)) {
                chain.doFilter(request, response);
                return;
            }
        }
        ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
}
