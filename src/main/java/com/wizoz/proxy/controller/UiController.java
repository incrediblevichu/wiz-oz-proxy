package com.wizoz.proxy.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the management page. It lives under {@code /wizoz} so it never collides with the proxied backend's paths. */
@RestController
public class UiController {

	@GetMapping(value = { "/wizoz/ui", "/wizoz/ui/" }, produces = MediaType.TEXT_HTML_VALUE)
	public Resource ui() {
		return new ClassPathResource("ui/index.html");
	}

}
