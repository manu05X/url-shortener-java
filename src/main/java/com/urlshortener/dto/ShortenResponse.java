package com.urlshortener.dto;

/**
 * The "envelope" for our response.
 * We send back both the original URL and the new short URL.
 */
public class ShortenResponse {

    private String originalUrl;
    private String shortUrl;

    public ShortenResponse() {
    }

    public ShortenResponse(String originalUrl, String shortUrl) {
        this.originalUrl = originalUrl;
        this.shortUrl = shortUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
    }
}
