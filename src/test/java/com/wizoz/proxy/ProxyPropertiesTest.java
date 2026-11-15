package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.wizoz.proxy.config.ProxyProperties;

class ProxyPropertiesTest {

	@Test
	void trailingSlashesAreDropped() {
		assertEquals("http://es:9200", new ProxyProperties("http://es:9200//", Duration.ofSeconds(1)).targetUrl());
		assertEquals("https://api.example.com/v1", new ProxyProperties(" https://api.example.com/v1/ ", Duration.ofSeconds(1)).targetUrl());
	}

	@Test
	void aTargetMustBeAnAbsoluteHttpUrl() {
		for (String bad : new String[] { null, "", "  ", "localhost:8888", "ftp://host", "http://", "/relative" }) {
			assertThrows(IllegalArgumentException.class, () -> new ProxyProperties(bad, Duration.ofSeconds(1)), String.valueOf(bad));
		}
	}

}
