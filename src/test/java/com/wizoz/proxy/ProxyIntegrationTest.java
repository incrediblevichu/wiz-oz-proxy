package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;
import com.wizoz.proxy.service.ProxyService;
import com.wizoz.proxy.service.RuleService;

/**
 * Runs the whole proxy in front of a small stub backend that echoes what it receives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ProxyIntegrationTest {

	private static int proxyPort;
	private static HttpServer backend;
	private static final AtomicInteger backendHits = new AtomicInteger();
	private static volatile String lastBackendBody = "";
	private static volatile String lastBackendQuery = "";
	private static volatile String lastBackendContentType = "";
	private static volatile int lastBackendCookieLength;

	private final HttpClient client = HttpClient.newHttpClient();

	@Autowired
	private RuleService rules;

	@Autowired
	private ProxyService proxyService;

	@Autowired
	private com.wizoz.proxy.service.TrafficService traffic;

	@BeforeAll
	static void startBackend() throws IOException {
		backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		backend.createContext("/", exchange -> {
			backendHits.incrementAndGet();
			lastBackendBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			lastBackendQuery = String.valueOf(exchange.getRequestURI().getRawQuery());
			lastBackendContentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
			lastBackendCookieLength = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")).length();
			byte[] body = ("backend:" + exchange.getRequestMethod() + ":" + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/big-headers")) {
				exchange.getResponseHeaders().add("Location", "https://consent.example.com/?continue=" + "a".repeat(20_000));
				exchange.getResponseHeaders().add("Set-Cookie", "session=" + "b".repeat(6_000));
				exchange.sendResponseHeaders(302, body.length);
			} else if (path.equals("/backend-error")) {
				exchange.sendResponseHeaders(418, body.length);
			} else {
				exchange.getResponseHeaders().add("X-From-Backend", "yes");
				exchange.sendResponseHeaders(200, body.length);
			}
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		backend.start();
	}

	@AfterAll
	static void stopBackend() {
		backend.stop(0);
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			proxyPort = socket.getLocalPort();
		}
		registry.add("server.port", () -> proxyPort);
		registry.add("wizoz.proxy.target-url", () -> "http://localhost:" + backend.getAddress().getPort());
		registry.add("wizoz.proxy.database-path", () -> ":memory:");
	}

	@BeforeEach
	void reset() {
		traffic.clear();
		rules.deleteAll();
		rules.setPaused(false);
		backendHits.set(0);
	}

	private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + path))
				.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
		if (contentType != null) {
			request.header("Content-Type", contentType);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return send("GET", path, null, null);
	}

	private HttpResponse<String> management(String method, String path, String json) throws Exception {
		return send(method, "/wizoz" + path, json, "application/json");
	}

	@Test
	void forwardsRequestsToTheBackendUnchanged() throws Exception {
		HttpResponse<String> response = get("/orders/7?x=1&y=a%20b");
		assertEquals(200, response.statusCode());
		assertEquals("backend:GET:/orders/7", response.body());
		assertEquals("yes", response.headers().firstValue("X-From-Backend").orElse(null));
		assertEquals("x=1&y=a%20b", lastBackendQuery);
	}

	@Test
	void relaysBackendResponsesWhoseHeadersAreFarLargerThanTomcatsDefault() throws Exception {
		HttpResponse<String> response = get("/big-headers");
		assertEquals(302, response.statusCode());
		assertTrue(response.headers().firstValue("Location").orElse("").length() > 20_000);
		assertEquals("backend:GET:/big-headers", response.body());
	}

	@Test
	void relaysLargeRequestHeadersLikeBrowserCookies() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/cookies"))
				.header("Cookie", "a=" + "c".repeat(30_000)).GET().build();
		assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
		assertEquals(30_002, lastBackendCookieLength);
	}

	@Test
	void relaysTheBackendsErrorStatusesToo() throws Exception {
		assertEquals(418, get("/backend-error").statusCode());
	}

	@Test
	void forwardsJsonBodiesAndFormBodies() throws Exception {
		assertEquals(200, send("POST", "/orders", "{\"a\":1}", "application/json").statusCode());
		assertEquals("{\"a\":1}", lastBackendBody);
		assertEquals("application/json", lastBackendContentType);

		assertEquals(200, send("POST", "/login", "user=a&pass=b", "application/x-www-form-urlencoded").statusCode());
		assertEquals("user=a&pass=b", lastBackendBody);
		assertEquals("application/x-www-form-urlencoded", lastBackendContentType);
	}

	@Test
	void forwardsEveryMethod() throws Exception {
		for (String method : List.of("PUT", "PATCH", "DELETE", "OPTIONS")) {
			assertEquals("backend:" + method + ":/thing", send(method, "/thing", "{}", "application/json").body());
		}
		assertEquals(200, send("HEAD", "/thing", null, null).statusCode());
		assertEquals(5, backendHits.get());
	}

	@Test
	void aMockedFailureIsAnsweredWithoutCallingTheBackend() throws Exception {
		management("POST", "/rules", "{\"pathContains\":\"/orders\",\"responseStatus\":503,\"responseBody\":\"{\\\"error\\\":\\\"down\\\"}\"}");
		HttpResponse<String> response = get("/orders/1");
		assertEquals(503, response.statusCode());
		assertEquals("{\"error\":\"down\"}", response.body());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
		assertEquals(0, backendHits.get());
		assertEquals(200, get("/users").statusCode());
	}

	@Test
	void errorPatternIsExactAndSurvivesConcurrentRequests() throws Exception {
		management("POST", "/rules", "{\"errorPattern\":\"S##2,F#503#3,S##1\"}");
		List<Integer> sequential = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			sequential.add(get("/p").statusCode());
		}
		assertEquals(List.of(200, 200, 503, 503, 503, 200, 200, 200, 200), sequential);

		rules.deleteAll();
		management("POST", "/rules", "{\"errorPattern\":\"S##10,F#503#3,S##1\"}");
		ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				results.add(executor.submit(() -> get("/p").statusCode()));
			}
			int failures = 0;
			for (Future<Integer> result : results) {
				if (result.get(30, TimeUnit.SECONDS) == 503) {
					failures++;
				}
			}
			assertEquals(3, failures);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void probabilityOfZeroNeverFailsAndOneHundredAlwaysFails() throws Exception {
		management("POST", "/rules", "{\"pathContains\":\"/never\",\"responseStatus\":500,\"errorProbability\":0}");
		management("POST", "/rules", "{\"pathContains\":\"/always\",\"responseStatus\":500,\"errorProbability\":100}");
		for (int i = 0; i < 20; i++) {
			assertEquals(200, get("/never").statusCode());
			assertEquals(500, get("/always").statusCode());
		}
	}

	@Test
	void aDelayRuleHoldsTheRequestThenForwardsIt() throws Exception {
		management("POST", "/rules", "{\"delayMs\":400}");
		long start = System.nanoTime();
		HttpResponse<String> response = get("/slow");
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertEquals(200, response.statusCode());
		assertTrue(elapsedMs >= 390, "took only " + elapsedMs + " ms");
		assertEquals(1, backendHits.get());
	}

	@Test
	void methodSpecificRulesLeaveOtherMethodsAlone() throws Exception {
		management("POST", "/rules", "{\"method\":\"POST\",\"responseStatus\":500}");
		assertEquals(500, send("POST", "/x", "{}", "application/json").statusCode());
		assertEquals(200, get("/x").statusCode());
	}

	@Test
	void headerBasedRulesApplyOnlyWhenHeaderMatches() throws Exception {
		management("POST", "/rules", "{\"headerName\":\"X-Test-Fault\",\"headerValue\":\"trigger-503\",\"responseStatus\":503}");

		HttpRequest withHeader = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/api/data"))
				.header("X-Test-Fault", "trigger-503").GET().build();
		assertEquals(503, client.send(withHeader, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest withWrongValue = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/api/data"))
				.header("X-Test-Fault", "other").GET().build();
		assertEquals(200, client.send(withWrongValue, HttpResponse.BodyHandlers.ofString()).statusCode());

		assertEquals(200, get("/api/data").statusCode());
	}

	@Test
	void queryParamBasedRulesApplyOnlyWhenQueryParamMatches() throws Exception {
		management("POST", "/rules", "{\"queryParamName\":\"fault\",\"queryParamValue\":\"true\",\"responseStatus\":500}");

		assertEquals(500, get("/search?fault=true").statusCode());
		assertEquals(200, get("/search?fault=false").statusCode());
		assertEquals(200, get("/search").statusCode());
		assertEquals(500, get("/search?category=books&fault=true&page=1").statusCode());
	}

	@Test
	void headerAndQueryParamRulesCanBeCombinedWithMethodAndPath() throws Exception {
		management("POST", "/rules", "{\"method\":\"POST\",\"pathContains\":\"/orders\",\"headerKey\":\"X-Env\",\"headerValue\":\"mock\",\"queryParamKey\":\"dryRun\",\"queryParamValue\":\"1\",\"responseStatus\":422}");

		HttpRequest matching = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/orders/create?dryRun=1"))
				.header("X-Env", "mock").POST(BodyPublishers.noBody()).build();
		assertEquals(422, client.send(matching, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest wrongHeader = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/orders/create?dryRun=1"))
				.header("X-Env", "real").POST(BodyPublishers.noBody()).build();
		assertEquals(200, client.send(wrongHeader, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest wrongQuery = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/orders/create?dryRun=0"))
				.header("X-Env", "mock").POST(BodyPublishers.noBody()).build();
		assertEquals(200, client.send(wrongQuery, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest wrongMethod = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/orders/create?dryRun=1"))
				.header("X-Env", "mock").GET().build();
		assertEquals(200, client.send(wrongMethod, HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void multipleHeadersAndMultipleQueryParamsCanBeMatchedSimultaneously() throws Exception {
		management("POST", "/rules", "{\"headers\":{\"X-Tenant\":\"tenant-1\",\"X-Role\":\"admin\"},\"queryParams\":{\"env\":\"sandbox\",\"debug\":\"true\"},\"responseStatus\":418}");

		HttpRequest matching = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/data?env=sandbox&debug=true"))
				.header("X-Tenant", "tenant-1").header("X-Role", "admin").GET().build();
		assertEquals(418, client.send(matching, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest missingHeader = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/data?env=sandbox&debug=true"))
				.header("X-Tenant", "tenant-1").GET().build();
		assertEquals(200, client.send(missingHeader, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest missingQuery = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/data?env=sandbox"))
				.header("X-Tenant", "tenant-1").header("X-Role", "admin").GET().build();
		assertEquals(200, client.send(missingQuery, HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void anyMatchModeAppliesWhenAtLeastOneHeaderOrQueryParamMatches() throws Exception {
		management("POST", "/rules", "{\"headers\":{\"X-A\":\"1\",\"X-B\":\"2\"},\"headerMatchMode\":\"ANY\",\"queryParams\":{\"p1\":\"a\",\"p2\":\"b\"},\"queryParamMatchMode\":\"ANY\",\"responseStatus\":503}");

		HttpRequest matchFirstOfEach = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/check?p1=a"))
				.header("X-A", "1").GET().build();
		assertEquals(503, client.send(matchFirstOfEach, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest matchSecondOfEach = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/check?p2=b"))
				.header("X-B", "2").GET().build();
		assertEquals(503, client.send(matchSecondOfEach, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest matchHeaderOnly = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/check?p1=wrong"))
				.header("X-A", "1").GET().build();
		assertEquals(200, client.send(matchHeaderOnly, HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void disablePauseAndDeleteStopARuleFromApplying() throws Exception {
		String created = management("POST", "/rules", "{\"responseStatus\":500}").body();
		String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
		assertEquals(500, get("/x").statusCode());

		management("POST", "/rules/" + id + "/disable", null);
		assertEquals(200, get("/x").statusCode());
		management("POST", "/rules/" + id + "/enable", null);
		assertEquals(500, get("/x").statusCode());

		management("POST", "/pause", null);
		assertEquals(200, get("/x").statusCode());
		management("POST", "/resume", null);
		assertEquals(500, get("/x").statusCode());

		assertEquals(204, management("DELETE", "/rules/" + id, null).statusCode());
		assertEquals(200, get("/x").statusCode());
	}

	@Test
	void deleteAllRemovesGlobalAndSpecificRulesAlike() throws Exception {
		management("POST", "/rules", "{\"responseStatus\":500}");
		management("POST", "/rules", "{\"pathContains\":\"/a\",\"responseStatus\":500}");
		assertEquals(204, management("DELETE", "/rules", null).statusCode());
		assertEquals("[]", management("GET", "/rules", null).body());
		assertEquals(200, get("/a").statusCode());
	}

	@Test
	void invalidRulesGetAJsonErrorAndAreNotStored() throws Exception {
		HttpResponse<String> response = management("POST", "/rules", "{\"errorPattern\":\"nonsense\"}");
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("\"message\""));
		assertEquals(400, management("POST", "/rules", "{}").statusCode());
		assertEquals(400, management("POST", "/rules", "not json").statusCode());
		assertEquals(400, management("POST", "/rules", "{\"responseStatus\":42}").statusCode());
		assertEquals("[]", management("GET", "/rules", null).body());
	}

	@Test
	void unknownRuleIdsAre404() throws Exception {
		assertEquals(404, management("GET", "/rules/nope", null).statusCode());
		assertEquals(404, management("DELETE", "/rules/nope", null).statusCode());
		assertEquals(404, management("POST", "/rules/nope/enable", null).statusCode());
		assertEquals(404, management("PUT", "/rules/nope", "{\"responseStatus\":500}").statusCode());
	}

	@Test
	void putReplacesARule() throws Exception {
		String created = management("POST", "/rules", "{\"pathContains\":\"/a\",\"responseStatus\":500}").body();
		String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
		assertEquals(200, management("PUT", "/rules/" + id, "{\"pathContains\":\"/a\",\"responseStatus\":502}").statusCode());
		assertEquals(502, get("/a").statusCode());
	}

	@Test
	void statisticsCountWhatHappened() throws Exception {
		management("POST", "/rules", "{\"pathContains\":\"/fail\",\"responseStatus\":500}");
		management("POST", "/rules", "{\"pathContains\":\"/slow\",\"delayMs\":50}");
		long before = proxyService.statistics().totalRequests();
		get("/ok");
		get("/fail");
		get("/slow");
		var stats = proxyService.statistics();
		assertEquals(before + 3, stats.totalRequests());
		assertEquals(2, stats.totalRules());
		assertEquals(2, stats.activeRules());
		assertEquals(1, stats.activeDelays());
		assertTrue(stats.totalMockedRequests() >= 1);
		assertTrue(stats.totalDelayedRequests() >= 1);
		assertEquals(200, management("GET", "/statistics", null).statusCode());
	}

	@Test
	void nothingUnderTheReservedPrefixIsEverForwarded() throws Exception {
		for (String path : List.of("/wizoz", "/wizoz/", "/wizoz/nonexistent", "/wizoz/rules/a/b", "/wizoz/uix")) {
			HttpResponse<String> response = get(path);
			assertEquals(404, response.statusCode(), path);
			assertTrue(response.body().contains("\"message\""), path);
		}
		assertEquals(404, send("PATCH", "/wizoz/rules", "{}", "application/json").statusCode());
		assertEquals(404, send("OPTIONS", "/wizoz/rules", null, null).statusCode());
		assertEquals(0, backendHits.get());
	}

	@Test
	void pathsThatMerelyStartWithTheSameLettersAreStillForwarded() throws Exception {
		assertEquals(200, get("/wizozzy/thing").statusCode());
		assertEquals(1, backendHits.get());
	}

	@Test
	void theRequestLogShowsProxiedTrafficAndNeverManagementTraffic() throws Exception {
		management("POST", "/rules", "{\"pathContains\":\"/mocked\",\"responseStatus\":503,\"responseBody\":\"{\\\"error\\\":\\\"down\\\"}\"}");
		get("/wizoz/ui");
		get("/wizoz/statistics");
		send("POST", "/orders?x=1", "{\"item\":42}", "application/json");
		HttpRequest withOrigin = HttpRequest.newBuilder(URI.create("http://localhost:" + proxyPort + "/mocked/1"))
				.header("Origin", "http://shop.example").GET().build();
		client.send(withOrigin, HttpResponse.BodyHandlers.ofString());

		var log = traffic.recent();
		assertEquals(2, log.size(), "management calls must not be logged: " + log);
		var mocked = log.get(0);
		assertEquals("/mocked/1", mocked.path());
		assertEquals("http://shop.example", mocked.origin());
		assertTrue(mocked.mocked());
		assertEquals(503, mocked.status());
		assertEquals("{\"error\":\"down\"}", mocked.response().body());
		assertTrue(mocked.ruleId() != null && !mocked.ruleId().isEmpty());

		var real = log.get(1);
		assertEquals("POST", real.method());
		assertEquals("/orders", real.path());
		assertEquals("x=1", real.query());
		assertEquals("{\"item\":42}", real.request().body());
		assertEquals(false, real.mocked());
		assertEquals(200, real.status());
		assertEquals("backend:POST:/orders", real.response().body());
		assertEquals(null, real.origin());
		assertEquals(null, real.ruleId());
	}

	@Test
	void aMockedRequestKeepsItsBodyForTheLog() throws Exception {
		management("POST", "/rules", "{\"responseStatus\":500}");
		send("POST", "/anything", "{\"secret\":1}", "application/json");
		assertEquals("{\"secret\":1}", traffic.recent().get(0).request().body());
		assertEquals(0, backendHits.get());
	}

	@Test
	void aDelayedRequestRecordsTheDelayAndTheTimeTaken() throws Exception {
		management("POST", "/rules", "{\"delayMs\":300}");
		get("/slow");
		var entry = traffic.recent().get(0);
		assertEquals(300, entry.delayMs());
		assertEquals(false, entry.mocked());
		assertTrue(entry.durationMs() >= 290, "took " + entry.durationMs());
	}

	@Test
	void anUnreachableBackendIsLoggedAs502AndNotAsMocked() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		var properties = new com.wizoz.proxy.config.ProxyProperties("http://localhost:" + closedPort, java.time.Duration.ofSeconds(2));
		var service = new com.wizoz.proxy.service.impl.ProxyServiceImpl(HttpClient.newHttpClient(), properties, rules, traffic, java.time.Clock.systemUTC());
		service.handle(new org.springframework.mock.web.MockHttpServletRequest("GET", "/x"), new org.springframework.mock.web.MockHttpServletResponse());
		var entry = traffic.recent().get(0);
		assertEquals(502, entry.status());
		assertEquals(false, entry.mocked());
	}

	@Test
	void requestLogAndTimelineAreServedAndClearable() throws Exception {
		int forwardedBefore = traffic.timeline().stream().mapToInt(p -> p.forwarded()).sum();
		get("/one");
		get("/two");
		HttpResponse<String> log = management("GET", "/requests", null);
		assertEquals(200, log.statusCode());
		assertTrue(log.body().indexOf("/two") < log.body().indexOf("/one"), "newest first");
		assertEquals(200, management("GET", "/requests/", null).statusCode());

		HttpResponse<String> timeline = management("GET", "/statistics/timeline", null);
		assertEquals(200, timeline.statusCode());
		assertEquals(forwardedBefore + 2, traffic.timeline().stream().mapToInt(p -> p.forwarded()).sum());
		assertTrue(timeline.body().startsWith("[{\"startMs\":"), timeline.body());

		assertEquals(204, management("DELETE", "/requests", null).statusCode());
		assertEquals("[]", management("GET", "/requests", null).body());
		assertEquals(forwardedBefore + 2, traffic.timeline().stream().mapToInt(p -> p.forwarded()).sum(), "clear keeps the timeline");
	}

	@Test
	void openingTheManagementPageIsNotCountedAsProxiedTraffic() throws Exception {
		long before = proxyService.statistics().totalRequests();
		HttpResponse<String> page = get("/wizoz/ui");
		get("/wizoz/rules");
		get("/wizoz/statistics");
		assertTrue(page.body().contains("rel=\"icon\""), "the page must declare an icon so browsers don't request /favicon.ico");
		assertEquals(before, proxyService.statistics().totalRequests());
		assertEquals(0, backendHits.get());
	}

	@Test
	void managementPathsWorkWithATrailingSlash() throws Exception {
		assertEquals(200, get("/wizoz/rules/").statusCode());
		assertEquals(200, get("/wizoz/ui/").statusCode());
		assertEquals(200, get("/wizoz/ui").statusCode());
		assertEquals(201, management("POST", "/rules/", "{\"responseStatus\":500}").statusCode());
		assertEquals(204, management("DELETE", "/rules/", null).statusCode());
		assertEquals(0, backendHits.get());
	}

	@Test
	void anUnreachableBackendGivesA502() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		var properties = new com.wizoz.proxy.config.ProxyProperties("http://localhost:" + closedPort, java.time.Duration.ofSeconds(2));
		var service = new com.wizoz.proxy.service.impl.ProxyServiceImpl(HttpClient.newHttpClient(), properties, rules, traffic, java.time.Clock.systemUTC());
		var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
		var response = new org.springframework.mock.web.MockHttpServletResponse();
		service.handle(request, response);
		assertEquals(502, response.getStatus());
	}

}
