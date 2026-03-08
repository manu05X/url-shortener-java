package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("URL Shortener Controller Integration Tests")
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlRepository urlRepository;

    @BeforeEach
    void setUp() {
        urlRepository.clear();
    }

    @Test
    @DisplayName("POST /api/shorten returns 201 with shortened URL")
    void shortenUrlShouldReturn201WithShortenedUrl() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com/search?q=hello");

        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalUrl").value("https://www.google.com/search?q=hello"))
                .andExpect(jsonPath("$.shortUrl").value(startsWith("http://localhost:8080/")));
    }

    @Test
    @DisplayName("POST /api/shorten returns same URL for duplicate request")
    void shortenUrlShouldReturnSameUrlForDuplicate() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com");

        String firstResponse = mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertEquals(firstResponse, secondResponse);
    }

    @Test
    @DisplayName("POST /api/shorten returns 400 for invalid URL")
    void shortenUrlShouldReturn400ForInvalidUrl() throws Exception {
        ShortenRequest request = new ShortenRequest("not-a-valid-url");

        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_URL"));
    }

    @Test
    @DisplayName("POST /api/shorten returns 400 for blank URL")
    void shortenUrlShouldReturn400ForBlankUrl() throws Exception {
        ShortenRequest request = new ShortenRequest("");

        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    private void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + " but got: " + actual);
        }
    }
}
