package com.urlshortener.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a mapping between an original (long) URL and its shortened code.
 * This is our core domain object — like a dictionary entry where
 * the short code is the "word" and the original URL is the "definition".
 */
public class UrlMapping {

    private final String originalUrl;
    private final String shortCode;
    private final LocalDateTime createdAt;

    public UrlMapping(String originalUrl, String shortCode) {
        this.originalUrl = originalUrl;
        this.shortCode = shortCode;
        this.createdAt = LocalDateTime.now();
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getShortCode() {
        return shortCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UrlMapping that = (UrlMapping) o;
        return Objects.equals(originalUrl, that.originalUrl)
                && Objects.equals(shortCode, that.shortCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalUrl, shortCode);
    }

    @Override
    public String toString() {
        return "UrlMapping{originalUrl='" + originalUrl + "', shortCode='" + shortCode + "'}";
    }
}
