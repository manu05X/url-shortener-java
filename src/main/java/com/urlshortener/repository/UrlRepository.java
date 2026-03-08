package com.urlshortener.repository;

import com.urlshortener.model.UrlMapping;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory storage for URL mappings using two ConcurrentHashMaps:
 *
 * 1. shortCodeToMapping: shortCode -> UrlMapping  (for redirect lookups)
 * 2. originalUrlToShortCode: originalUrl -> shortCode  (to return same code for duplicate URLs)
 *
 * Two maps give us O(1) lookups in both directions — like having
 * both an English-to-Spanish AND a Spanish-to-English dictionary.
 */
@Repository
public class UrlRepository {

    private final ConcurrentHashMap<String, UrlMapping> shortCodeToMapping = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> originalUrlToShortCode = new ConcurrentHashMap<>();

    public UrlMapping save(UrlMapping mapping) {
        shortCodeToMapping.put(mapping.getShortCode(), mapping);
        originalUrlToShortCode.put(mapping.getOriginalUrl(), mapping.getShortCode());
        return mapping;
    }

    public Optional<UrlMapping> findByShortCode(String shortCode) {
        return Optional.ofNullable(shortCodeToMapping.get(shortCode));
    }

    public Optional<String> findShortCodeByOriginalUrl(String originalUrl) {
        return Optional.ofNullable(originalUrlToShortCode.get(originalUrl));
    }

    public boolean shortCodeExists(String shortCode) {
        return shortCodeToMapping.containsKey(shortCode);
    }

    public Collection<UrlMapping> findAll() {
        return shortCodeToMapping.values();
    }

    public long count() {
        return shortCodeToMapping.size();
    }

    public void clear() {
        shortCodeToMapping.clear();
        originalUrlToShortCode.clear();
    }
}
