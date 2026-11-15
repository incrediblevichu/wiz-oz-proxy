package com.wizoz.proxy.service;

import java.io.IOException;

import com.wizoz.proxy.model.Statistics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface ProxyService {

	/**
	 * Answers one request: applies the first matching rule (delay, then a mocked failure) and otherwise forwards the
	 * request to the target backend and relays its response unchanged.
	 */
	void handle(HttpServletRequest request, HttpServletResponse response) throws IOException;

	Statistics statistics();

}
