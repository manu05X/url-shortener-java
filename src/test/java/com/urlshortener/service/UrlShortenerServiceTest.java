package com.urlshortener.service;

import com.urlshortener.dto.DomainMetric;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UrlShortenerService Unit Tests")
class UrlShortenerServiceTest {

    private UrlRepository urlRepository;
    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        urlRepository = new UrlRepository();
        service = new UrlShortenerService(urlRepository, "http://localhost:8080", 6);
    }

    @Test
    @DisplayName("shortenUrl() creates a new mapping for a new URL")
    void shortenUrlShouldCreateNewMapping() {
        UrlMapping result = service.shortenUrl("https://www.google.com/search?q=hello");

        assertNotNull(result);
        assertEquals("https://www.google.com/search?q=hello", result.getOriginalUrl());
        assertNotNull(result.getShortCode());
        assertEquals(6, result.getShortCode().length());
    }

    @Test
    @DisplayName("shortenUrl() returns same mapping for duplicate URL")
    void shortenUrlShouldReturnSameMappingForDuplicateUrl() {
        UrlMapping first = service.shortenUrl("https://www.google.com");
        UrlMapping second = service.shortenUrl("https://www.google.com");

        assertEquals(first.getShortCode(), second.getShortCode());
        assertEquals(first.getOriginalUrl(), second.getOriginalUrl());
    }

    @Test
    @DisplayName("shortenUrl() creates different codes for different URLs")
    void shortenUrlShouldCreateDifferentCodesForDifferentUrls() {
        UrlMapping first = service.shortenUrl("https://www.google.com");
        UrlMapping second = service.shortenUrl("https://www.github.com");

        assertNotEquals(first.getShortCode(), second.getShortCode());
    }

    @Test
    @DisplayName("resolveShortCode() returns mapping for existing code")
    void resolveShortCodeShouldReturnMappingForExistingCode() {
        UrlMapping mapping = service.shortenUrl("https://www.google.com");

        Optional<UrlMapping> resolved = service.resolveShortCode(mapping.getShortCode());

        assertTrue(resolved.isPresent());
        assertEquals("https://www.google.com", resolved.get().getOriginalUrl());
    }

    @Test
    @DisplayName("resolveShortCode() returns empty for non-existent code")
    void resolveShortCodeShouldReturnEmptyForNonExistentCode() {
        Optional<UrlMapping> resolved = service.resolveShortCode("nonexistent");
        assertTrue(resolved.isEmpty());
    }

    @Test
    @DisplayName("getTopDomains() returns top 3 domains sorted by count")
    void getTopDomainsShouldReturnTop3Sorted() {
        // 4 YouTube links
        service.shortenUrl("https://www.youtube.com/watch?v=1");
        service.shortenUrl("https://www.youtube.com/watch?v=2");
        service.shortenUrl("https://www.youtube.com/watch?v=3");
        service.shortenUrl("https://www.youtube.com/watch?v=4");

        // 1 StackOverflow link
        service.shortenUrl("https://stackoverflow.com/questions/1");

        // 2 Wikipedia links
        service.shortenUrl("https://en.wikipedia.org/wiki/Java");
        service.shortenUrl("https://en.wikipedia.org/wiki/Python");

        // 6 Udemy links
        service.shortenUrl("https://www.udemy.com/course/1");
        service.shortenUrl("https://www.udemy.com/course/2");
        service.shortenUrl("https://www.udemy.com/course/3");
        service.shortenUrl("https://www.udemy.com/course/4");
        service.shortenUrl("https://www.udemy.com/course/5");
        service.shortenUrl("https://www.udemy.com/course/6");

        List<DomainMetric> topDomains = service.getTopDomains();

        assertEquals(3, topDomains.size());
        assertEquals("udemy.com", topDomains.get(0).getDomain());
        assertEquals(6, topDomains.get(0).getCount());
        assertEquals("youtube.com", topDomains.get(1).getDomain());
        assertEquals(4, topDomains.get(1).getCount());
        assertEquals("en.wikipedia.org", topDomains.get(2).getDomain());
        assertEquals(2, topDomains.get(2).getCount());
    }

    @Test
    @DisplayName("getTopDomains() returns empty list when no URLs shortened")
    void getTopDomainsShouldReturnEmptyWhenNoUrls() {
        List<DomainMetric> topDomains = service.getTopDomains();
        assertTrue(topDomains.isEmpty());
    }

    @Test
    @DisplayName("getTopDomains() returns fewer than 3 when less domains exist")
    void getTopDomainsShouldReturnFewerThan3WhenLessDomains() {
        service.shortenUrl("https://www.google.com/search?q=1");
        service.shortenUrl("https://www.google.com/search?q=2");

        List<DomainMetric> topDomains = service.getTopDomains();

        assertEquals(1, topDomains.size());
        assertEquals("google.com", topDomains.get(0).getDomain());
        assertEquals(2, topDomains.get(0).getCount());
    }

    @Test
    @DisplayName("buildShortUrl() constructs full URL from short code")
    void buildShortUrlShouldConstructFullUrl() {
        String shortUrl = service.buildShortUrl("abc123");
        assertEquals("http://localhost:8080/abc123", shortUrl);
    }

    @Test
    @DisplayName("generateShortCode() produces consistent length codes")
    void generateShortCodeShouldProduceConsistentLengthCodes() {
        String code = service.generateShortCode("https://www.example.com");
        assertEquals(6, code.length());
    }

    @Test
    @DisplayName("hashToBase62() produces only alphanumeric characters")
    void hashToBase62ShouldProduceAlphanumericOnly() {
        String hash = service.hashToBase62("test-input", 10);
        assertTrue(hash.matches("[a-zA-Z0-9]+"));
    }

    @Test
    @DisplayName("extractDomain() strips www prefix")
    void extractDomainShouldStripWwwPrefix() {
        assertEquals("google.com", service.extractDomain("https://www.google.com/search"));
    }

    @Test
    @DisplayName("extractDomain() keeps non-www subdomains")
    void extractDomainShouldKeepNonWwwSubdomains() {
        assertEquals("en.wikipedia.org", service.extractDomain("https://en.wikipedia.org/wiki/Java"));
    }

    @Test
    @DisplayName("extractDomain() returns empty for invalid URL")
    void extractDomainShouldReturnEmptyForInvalidUrl() {
        assertEquals("", service.extractDomain("not-a-url"));
    }
}
