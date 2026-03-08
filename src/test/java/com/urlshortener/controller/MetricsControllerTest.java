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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Metrics Controller Integration Tests")
class MetricsControllerTest {

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
    @DisplayName("GET /api/metrics/top-domains returns empty list when no URLs")
    void topDomainsShouldReturnEmptyListWhenNoUrls() throws Exception {
        mockMvc.perform(get("/api/metrics/top-domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/metrics/top-domains returns top 3 domains sorted by count")
    void topDomainsShouldReturnTop3Sorted() throws Exception {
        shortenUrl("https://www.youtube.com/watch?v=1");
        shortenUrl("https://www.youtube.com/watch?v=2");
        shortenUrl("https://www.youtube.com/watch?v=3");
        shortenUrl("https://www.youtube.com/watch?v=4");

        shortenUrl("https://stackoverflow.com/questions/1");

        shortenUrl("https://en.wikipedia.org/wiki/Java");
        shortenUrl("https://en.wikipedia.org/wiki/Python");

        shortenUrl("https://www.udemy.com/course/1");
        shortenUrl("https://www.udemy.com/course/2");
        shortenUrl("https://www.udemy.com/course/3");
        shortenUrl("https://www.udemy.com/course/4");
        shortenUrl("https://www.udemy.com/course/5");
        shortenUrl("https://www.udemy.com/course/6");

        mockMvc.perform(get("/api/metrics/top-domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].domain").value("udemy.com"))
                .andExpect(jsonPath("$[0].count").value(6))
                .andExpect(jsonPath("$[1].domain").value("youtube.com"))
                .andExpect(jsonPath("$[1].count").value(4))
                .andExpect(jsonPath("$[2].domain").value("en.wikipedia.org"))
                .andExpect(jsonPath("$[2].count").value(2));
    }

    private void shortenUrl(String url) throws Exception {
        ShortenRequest request = new ShortenRequest(url);
        mockMvc.perform(post("/api/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}
