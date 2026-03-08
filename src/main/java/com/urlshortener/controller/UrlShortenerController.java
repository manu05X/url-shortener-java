package com.urlshortener.controller;

import com.urlshortener.dto.ErrorResponse;
import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.service.UrlShortenerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;

    public UrlShortenerController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @PostMapping("/shorten")
    public ResponseEntity<?> shortenUrl(@Valid @RequestBody ShortenRequest request) {
        String originalUrl = request.getUrl().trim();

        if (!isValidUrl(originalUrl)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("INVALID_URL", "The provided URL is not valid. Please include the protocol (http:// or https://)."));
        }

        UrlMapping mapping = urlShortenerService.shortenUrl(originalUrl);
        String shortUrl = urlShortenerService.buildShortUrl(mapping.getShortCode());

        ShortenResponse response = new ShortenResponse(mapping.getOriginalUrl(), shortUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private boolean isValidUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
