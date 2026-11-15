package com.wizoz.proxy.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.wizoz.proxy.service.ProxyService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Receives every request that no management endpoint claims. The {@code /wizoz} prefix belongs to the proxy, so a
 * request under it that matches nothing is answered with a 404 here, never forwarded to the backend.
 */
@RestController
public class ProxyController {

	private static final String RESERVED_PREFIX = "/wizoz";

	private final ProxyService proxyService;

	public ProxyController(ProxyService proxyService) {
		this.proxyService = proxyService;
	}

	// OPTIONS has to be listed: Spring answers it itself for a mapping that doesn't name it.
	@RequestMapping(value = "/**", method = { RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST, RequestMethod.PUT,
			RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS, RequestMethod.TRACE })
	public ResponseEntity<Map<String, String>> proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String path = request.getRequestURI();
		if (path.equals(RESERVED_PREFIX) || path.startsWith(RESERVED_PREFIX + "/")) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("message", "There is no management endpoint " + request.getMethod() + " " + path));
		}
		proxyService.handle(request, response);
		return null;
	}

}
