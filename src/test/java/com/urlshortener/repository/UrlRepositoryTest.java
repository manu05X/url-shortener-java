package com.urlshortener.repository;

import com.urlshortener.model.UrlMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UrlRepository Unit Tests")
class UrlRepositoryTest {

    private UrlRepository repository;

    @BeforeEach
    void setUp() {
        repository = new UrlRepository();
    }

    @Test
    @DisplayName("save() stores mapping and makes it retrievable by short code")
    void saveShouldStoreMappingRetrievableByShortCode() {
        UrlMapping mapping = new UrlMapping("https://www.google.com", "abc123");

        repository.save(mapping);

        Optional<UrlMapping> found = repository.findByShortCode("abc123");
        assertTrue(found.isPresent());
        assertEquals("https://www.google.com", found.get().getOriginalUrl());
    }

    @Test
    @DisplayName("save() stores mapping and makes it retrievable by original URL")
    void saveShouldStoreMappingRetrievableByOriginalUrl() {
        UrlMapping mapping = new UrlMapping("https://www.google.com", "abc123");

        repository.save(mapping);

        Optional<String> shortCode = repository.findShortCodeByOriginalUrl("https://www.google.com");
        assertTrue(shortCode.isPresent());
        assertEquals("abc123", shortCode.get());
    }

    @Test
    @DisplayName("findByShortCode() returns empty for non-existent code")
    void findByShortCodeShouldReturnEmptyForNonExistent() {
        Optional<UrlMapping> found = repository.findByShortCode("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("findShortCodeByOriginalUrl() returns empty for non-existent URL")
    void findShortCodeByOriginalUrlShouldReturnEmptyForNonExistent() {
        Optional<String> found = repository.findShortCodeByOriginalUrl("https://nonexistent.com");
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("shortCodeExists() returns true for existing code")
    void shortCodeExistsShouldReturnTrueForExisting() {
        repository.save(new UrlMapping("https://www.google.com", "abc123"));
        assertTrue(repository.shortCodeExists("abc123"));
    }

    @Test
    @DisplayName("shortCodeExists() returns false for non-existent code")
    void shortCodeExistsShouldReturnFalseForNonExistent() {
        assertFalse(repository.shortCodeExists("nonexistent"));
    }

    @Test
    @DisplayName("findAll() returns all stored mappings")
    void findAllShouldReturnAllMappings() {
        repository.save(new UrlMapping("https://www.google.com", "abc123"));
        repository.save(new UrlMapping("https://www.github.com", "def456"));

        assertEquals(2, repository.findAll().size());
    }

    @Test
    @DisplayName("count() returns correct number of stored mappings")
    void countShouldReturnCorrectNumber() {
        assertEquals(0, repository.count());

        repository.save(new UrlMapping("https://www.google.com", "abc123"));
        assertEquals(1, repository.count());

        repository.save(new UrlMapping("https://www.github.com", "def456"));
        assertEquals(2, repository.count());
    }

    @Test
    @DisplayName("clear() removes all mappings")
    void clearShouldRemoveAllMappings() {
        repository.save(new UrlMapping("https://www.google.com", "abc123"));
        repository.save(new UrlMapping("https://www.github.com", "def456"));

        repository.clear();

        assertEquals(0, repository.count());
        assertTrue(repository.findByShortCode("abc123").isEmpty());
        assertTrue(repository.findShortCodeByOriginalUrl("https://www.google.com").isEmpty());
    }
}
