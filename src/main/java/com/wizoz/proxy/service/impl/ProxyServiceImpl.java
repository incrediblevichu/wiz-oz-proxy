package com.wizoz.proxy.service.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Enumeration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wizoz.proxy.config.ProxyProperties;
import com.wizoz.proxy.model.Payload;
import com.wizoz.proxy.model.RequestRecord;
import com.wizoz.proxy.model.Rule;
import com.wizoz.proxy.model.RuleView;
import com.wizoz.proxy.model.Statistics;
import com.wizoz.proxy.service.ProxyService;
import com.wizoz.proxy.service.RuleService;
import com.wizoz.proxy.service.TrafficService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class ProxyServiceImpl implements ProxyService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProxyServiceImpl.class);

	/** Headers the JDK client sets itself, and hop-by-hop headers that describe only one connection. */
	private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "content-length", "expect", "upgrade",
			"keep-alive", "transfer-encoding", "te", "trailer", "proxy-authorization", "proxy-connection");

	private final HttpClient client;
	private final ProxyProperties properties;
	private final RuleService rules;
	private final TrafficService traffic;
	private final Clock clock;

	/** What the client was sent, for the request log. */
	private record Outcome(int status, boolean mocked, Payload response) {
	}

	private final AtomicLong totalRequests = new AtomicLong();
	private final AtomicLong forwardedRequests = new AtomicLong();
	private final AtomicLong mockedRequests = new AtomicLong();
	private final AtomicLong delayedRequests = new AtomicLong();

	public ProxyServiceImpl(HttpClient client, ProxyProperties properties, RuleService rules, TrafficService traffic,
			Clock clock) {
		this.client = client;
		this.properties = properties;
		this.rules = rules;
		this.traffic = traffic;
		this.clock = clock;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
		totalRequests.incrementAndGet();
		long arrivedMs = clock.millis();
		long startNanos = System.nanoTime();
		byte[] body = request.getInputStream().readAllBytes();
		String method = request.getMethod();
		String path = request.getRequestURI();

		Optional<Rule> rule = rules.findApplicable(method, path, request);
		Outcome outcome = null;
		if (rule.isPresent()) {
			if (!applyDelay(rule.get())) {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Interrupted while delaying");
				outcome = failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Interrupted while delaying");
			} else {
				OptionalInt fault = rule.get().nextFault(() -> ThreadLocalRandom.current().nextInt(100) + 1);
				if (fault.isPresent()) {
					mockedRequests.incrementAndGet();
					writeMock(response, fault.getAsInt(), rule.get());
					outcome = new Outcome(fault.getAsInt(), true, Payload.ofText(rule.get().responseBody(), rule.get().contentType()));
				}
			}
		}
		if (outcome == null) {
			outcome = forward(request, body, response);
		}

		traffic.record(new RequestRecord(0, arrivedMs, method, path, request.getQueryString(), request.getRemoteAddr(),
				request.getHeader("Origin"), Payload.of(body, request.getContentType(), request.getHeader("Content-Encoding")),
				outcome.mocked(), rule.map(Rule::id).orElse(null), rule.map(Rule::delayMs).orElse(0), outcome.status(),
				outcome.response(), (System.nanoTime() - startNanos) / 1_000_000));
	}

	private boolean applyDelay(Rule rule) {
		if (rule.delayMs() <= 0) {
			return true;
		}
		delayedRequests.incrementAndGet();
		try {
			Thread.sleep(rule.delayMs());
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void writeMock(HttpServletResponse response, int status, Rule rule) throws IOException {
		byte[] body = rule.responseBody().getBytes(StandardCharsets.UTF_8);
		response.setStatus(status);
		response.setContentType(rule.contentType());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}

	private Outcome forward(HttpServletRequest request, byte[] body, HttpServletResponse response) throws IOException {
		HttpRequest.Builder upstream = HttpRequest.newBuilder(targetUri(request))
				.timeout(properties.requestTimeout())
				.method(request.getMethod(), BodyPublishers.ofByteArray(body));
		Enumeration<String> names = request.getHeaderNames();
		while (names.hasMoreElements()) {
			String name = names.nextElement();
			if (!NOT_FORWARDED.contains(name.toLowerCase())) {
				Enumeration<String> values = request.getHeaders(name);
				while (values.hasMoreElements()) {
					upstream.header(name, values.nextElement());
				}
			}
		}

		HttpResponse<byte[]> answer;
		try {
			answer = client.send(upstream.build(), BodyHandlers.ofByteArray());
		} catch (HttpTimeoutException e) {
			LOGGER.warn("Timed out waiting for {}", properties.targetUrl());
			response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "The target did not answer in time");
			return failure(HttpServletResponse.SC_GATEWAY_TIMEOUT, "The target did not answer in time");
		} catch (IOException e) {
			LOGGER.warn("Could not reach {}: {}", properties.targetUrl(), e.toString());
			String message = "Could not reach the target: " + e.getMessage();
			response.sendError(HttpServletResponse.SC_BAD_GATEWAY, message);
			return failure(HttpServletResponse.SC_BAD_GATEWAY, message);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Interrupted while forwarding");
			return failure(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Interrupted while forwarding");
		}

		forwardedRequests.incrementAndGet();
		response.setStatus(answer.statusCode());
		answer.headers().map().forEach((name, values) -> {
			if (!NOT_FORWARDED.contains(name.toLowerCase())) {
				values.forEach(value -> response.addHeader(name, value));
			}
		});
		response.setContentLength(answer.body().length);
		response.getOutputStream().write(answer.body());
		return new Outcome(answer.statusCode(), false, Payload.of(answer.body(),
				answer.headers().firstValue("content-type").orElse(null), answer.headers().firstValue("content-encoding").orElse(null)));
	}

	private static Outcome failure(int status, String message) {
		return new Outcome(status, false, Payload.ofText(message, "text/plain"));
	}

	private URI targetUri(HttpServletRequest request) {
		String query = request.getQueryString();
		return URI.create(properties.targetUrl() + request.getRequestURI() + (query == null ? "" : "?" + query));
	}

	@Override
	public Statistics statistics() {
		var all = rules.list();
		int active = (int) all.stream().filter(RuleView::enabled).count();
		int activeDelays = (int) all.stream().filter(r -> r.enabled() && r.delayMs() > 0).count();
		int delays = (int) all.stream().filter(r -> r.delayMs() > 0).count();
		return new Statistics(totalRequests.get(), forwardedRequests.get(), mockedRequests.get(), delayedRequests.get(),
				all.size(), active, all.size() - active, activeDelays, delays - activeDelays, rules.isPaused(), rules.getDbWarning());
	}

}
