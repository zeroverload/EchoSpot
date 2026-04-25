package com.zeroverload.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class VersionController {
    @GetMapping("/__version")
    public Map<String, Object> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "echospot-rental-backend");
        m.put("buildDate", "2026-04-14");
        return m;
    }
}

