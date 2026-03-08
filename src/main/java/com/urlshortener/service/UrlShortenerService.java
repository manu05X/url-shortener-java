package com.urlshortener.service;

import com.urlshortener.dto.DomainMetric;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.repository.UrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UrlShortenerService {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String BASE62_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TOP_DOMAINS_COUNT = 3;

    private final UrlRepository urlRepository;
    private final String baseUrl;
    private final int shortCodeLength;

    public UrlShortenerService(
            UrlRepository urlRepository,
            @Value("${app.base-url}") String baseUrl,
            @Value("${app.short-code-length}") int shortCodeLength) {
        this.urlRepository = urlRepository;
        this.baseUrl = baseUrl;
        this.shortCodeLength = shortCodeLength;
    }

    /**
     * Shortens a URL. If the same URL was shortened before, returns the same short URL.
     *
     * Algorithm:
     * 1. Check if this URL was already shortened (return existing if yes)
     * 2. Hash the URL using SHA-256
     * 3. Convert hash bytes to a Base62 string (a-z, A-Z, 0-9)
     * 4. Take the first N characters as the short code
     * 5. Handle collisions by appending a suffix and re-hashing
     */
    public UrlMapping shortenUrl(String originalUrl) {
        Optional<String> existingCode = urlRepository.findShortCodeByOriginalUrl(originalUrl);
        if (existingCode.isPresent()) {
            return urlRepository.findByShortCode(existingCode.get()).orElseThrow();
        }

        String shortCode = generateShortCode(originalUrl);
        UrlMapping mapping = new UrlMapping(originalUrl, shortCode);
        return urlRepository.save(mapping);
    }

    public Optional<UrlMapping> resolveShortCode(String shortCode) {
        return urlRepository.findByShortCode(shortCode);
    }

    /**
     * Returns the top 3 domains that have been shortened the most.
     *
     * How it works (like counting candy by color):
     * 1. Look at every shortened URL
     * 2. Extract the domain name (e.g., "www.youtube.com" -> "youtube.com")
     * 3. Count how many times each domain appears
     * 4. Sort by count (highest first) and take top 3
     */
    public List<DomainMetric> getTopDomains() {
        Map<String, Long> domainCounts = urlRepository.findAll().stream()
                .map(mapping -> extractDomain(mapping.getOriginalUrl()))
                .filter(domain -> !domain.isEmpty())
                .collect(Collectors.groupingBy(domain -> domain, Collectors.counting()));

        return domainCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_DOMAINS_COUNT)
                .map(entry -> new DomainMetric(entry.getKey(), entry.getValue()))
                .toList();
    }

    public String buildShortUrl(String shortCode) {
        return baseUrl + "/" + shortCode;
    }

    String generateShortCode(String url) {
        String candidate = url;
        int attempt = 0;

        while (true) {
            String code = hashToBase62(candidate, shortCodeLength);
            if (!urlRepository.shortCodeExists(code)) {
                return code;
            }
            attempt++;
            candidate = url + "#" + attempt;
        }
    }

    String hashToBase62(String input, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                int unsignedByte = b & 0xFF;
                sb.append(BASE62_CHARS.charAt(unsignedByte % BASE62_CHARS.length()));
                if (sb.length() >= length) break;
            }
            return sb.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Extracts the domain from a URL, stripping "www." prefix.
     * Example: "https://www.youtube.com/watch?v=123" -> "youtube.com"
     */
    String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }
}
