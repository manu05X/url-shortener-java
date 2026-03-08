package com.urlshortener.dto;

/**
 * Represents a single entry in the metrics response.
 * Example: { "domain": "youtube.com", "count": 4 }
 */
public class DomainMetric {

    private String domain;
    private long count;

    public DomainMetric() {
    }

    public DomainMetric(String domain, long count) {
        this.domain = domain;
        this.count = count;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
