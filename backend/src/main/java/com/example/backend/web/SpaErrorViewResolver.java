package com.example.backend.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

@Component
public class SpaErrorViewResolver implements ErrorViewResolver {

    @Override
    public ModelAndView resolveErrorView(
            HttpServletRequest request,
            HttpStatus status,
            Map<String, Object> model) {
        String path = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (status != HttpStatus.NOT_FOUND || path == null || !SpaRoutes.servesSpaIndex(path)) {
            return null;
        }
        // OK, or the SPA index keeps the 404 that routed the request here (/docs/domain/request-paths.md).
        return new ModelAndView("forward:/index.html", HttpStatus.OK);
    }
}
