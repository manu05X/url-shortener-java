package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The "envelope" for incoming shorten requests.
 * The user sends us a long URL inside this envelope.
 */
public class ShortenRequest {

    @NotBlank(message = "URL must not be blank")
    private String url;

    public ShortenRequest() {
    }

    public ShortenRequest(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
