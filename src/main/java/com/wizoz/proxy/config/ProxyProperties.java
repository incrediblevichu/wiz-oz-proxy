package com.wizoz.proxy.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where to forward traffic to. {@code targetUrl} must be an absolute http(s) URL; a trailing slash is dropped.
 */
@ConfigurationProperties("wizoz.proxy")
public record ProxyProperties(
		String targetUrl,
		@DefaultValue("30s") Duration requestTimeout,
		@DefaultValue("rules.db") String databasePath) {

	@ConstructorBinding
	public ProxyProperties(String targetUrl, Duration requestTimeout, String databasePath) {
		if (targetUrl == null || targetUrl.isBlank()) {
			throw new IllegalArgumentException("wizoz.proxy.target-url must be set");
		}
		URI uri = URI.create(targetUrl.trim());
		String scheme = uri.getScheme();
		boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
		if (!http || uri.getHost() == null) {
			throw new IllegalArgumentException("wizoz.proxy.target-url must be an absolute http(s) URL: " + targetUrl);
		}
		this.targetUrl = targetUrl.trim().replaceAll("/+$", "");
		this.requestTimeout = requestTimeout;
		this.databasePath = (databasePath == null || databasePath.isBlank()) ? "rules.db" : databasePath.trim();
	}

	public ProxyProperties(String targetUrl, Duration requestTimeout) {
		this(targetUrl, requestTimeout, "rules.db");
	}

	public static ProxyProperties of(String targetUrl, Duration requestTimeout) {
		return new ProxyProperties(targetUrl, requestTimeout, "rules.db");
	}

}
