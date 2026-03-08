package com.urlshortener.controller;

import com.urlshortener.dto.DomainMetric;
import com.urlshortener.service.UrlShortenerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Returns metrics about shortened URLs.
 * Currently supports: top 3 domains by number of shortened links.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final UrlShortenerService urlShortenerService;

    public MetricsController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @GetMapping("/top-domains")
    public ResponseEntity<List<DomainMetric>> getTopDomains() {
        List<DomainMetric> topDomains = urlShortenerService.getTopDomains();
        return ResponseEntity.ok(topDomains);
    }
}
