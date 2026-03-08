package com.urlshortener.controller;

import com.urlshortener.dto.ErrorResponse;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.service.UrlShortenerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/**
 * Handles redirection: when someone visits the short URL,
 * we send them to the original long URL using HTTP 302 redirect.
 *
 * HTTP 302 tells the browser: "The page you want is temporarily at this other address."
 * The browser then automatically goes to the original URL.
 */
@RestController
public class RedirectController {

    private final UrlShortenerService urlShortenerService;

    public RedirectController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<?> redirect(@PathVariable String shortCode) {
        Optional<UrlMapping> mapping = urlShortenerService.resolveShortCode(shortCode);

        if (mapping.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("NOT_FOUND", "Short URL not found: " + shortCode));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(mapping.get().getOriginalUrl()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}
