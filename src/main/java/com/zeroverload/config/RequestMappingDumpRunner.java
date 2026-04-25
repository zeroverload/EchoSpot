package com.zeroverload.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
@Slf4j
@ConditionalOnProperty(name = "rental.debug-mappings", havingValue = "true")
public class RequestMappingDumpRunner implements CommandLineRunner {
    @Resource
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Override
    public void run(String... args) {
        Map<RequestMappingInfo, HandlerMethod> map = requestMappingHandlerMapping.getHandlerMethods();
        Set<String> lines = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : map.entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod hm = e.getValue();
            if (info.getPatternsCondition() == null) continue;
            for (String p : info.getPatternsCondition().getPatterns()) {
                lines.add(p + " -> " + hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName());
            }
        }
        log.warn("=== RequestMappings (rental.debug-mappings=true) ===");
        for (String line : lines) {
            if (line.startsWith("/station") || line.startsWith("/rental") || line.startsWith("/user") || line.startsWith("/shop") || line.startsWith("/__version")) {
                log.warn(line);
            }
        }
        log.warn("=== End RequestMappings ===");
    }
}

