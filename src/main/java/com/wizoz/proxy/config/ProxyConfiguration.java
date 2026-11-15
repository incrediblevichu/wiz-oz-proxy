package com.wizoz.proxy.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class ProxyConfiguration {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	@Bean
	public HttpClient upstreamClient() {
		return HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5))
				.build();
	}

}
