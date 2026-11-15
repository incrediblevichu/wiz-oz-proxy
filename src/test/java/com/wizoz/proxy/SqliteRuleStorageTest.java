package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wizoz.proxy.config.ProxyProperties;
import com.wizoz.proxy.model.RuleRequest;
import com.wizoz.proxy.service.impl.RuleServiceImpl;
import com.wizoz.proxy.service.impl.SqliteRuleStorage;
import com.wizoz.proxy.service.impl.SqliteRuleStorage.PersistedRule;

class SqliteRuleStorageTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void initializesNewDatabaseFile(@TempDir Path tempDir) throws Exception {
		File dbFile = tempDir.resolve("test-rules.db").toFile();
		assertFalse(dbFile.exists());

		SqliteRuleStorage storage = new SqliteRuleStorage(mapper);
		String warning = storage.init(dbFile.getAbsolutePath());
		assertNull(warning);
		assertTrue(storage.isActive());
		assertTrue(dbFile.exists());

		RuleRequest req = new RuleRequest(true, "POST", "/orders", Map.of("X-Env", "test"), "ALL",
				Map.of("q", "1"), "ALL", null, null, null, null, 100, 500, "{\"error\":1}", "application/json", 100, "").normalized();

		storage.saveAll(List.of(new PersistedRule("rule-1", req)));

		List<PersistedRule> loaded = storage.loadAll();
		assertEquals(1, loaded.size());
		assertEquals("rule-1", loaded.get(0).id());
		assertEquals("POST", loaded.get(0).spec().method());
		assertEquals("/orders", loaded.get(0).spec().pathContains());
		assertEquals(Map.of("X-Env", "test"), loaded.get(0).spec().headers());
		assertEquals(Map.of("q", "1"), loaded.get(0).spec().queryParams());
		assertEquals(500, loaded.get(0).spec().responseStatus());
	}

	@Test
	void persistsAndRestoresRulesAcrossRuleServiceInstances(@TempDir Path tempDir) {
		File dbFile = tempDir.resolve("persist-rules.db").toFile();
		ProxyProperties props = new ProxyProperties("http://localhost:8888", java.time.Duration.ofSeconds(30), dbFile.getAbsolutePath());

		RuleServiceImpl service1 = new RuleServiceImpl(props, new SqliteRuleStorage(mapper));
		assertNull(service1.getDbWarning());

		String id = service1.add(new RuleRequest(true, "GET", "/api", Map.of("X-Tenant", "t1"), "ANY",
				Map.of("p", "v"), "ANY", null, null, null, null, 50, 503, "{\"down\":true}", "application/json", 100, ""));
		service1.flushDbSync();
		service1.shutdown();

		RuleServiceImpl service2 = new RuleServiceImpl(props, new SqliteRuleStorage(mapper));
		assertNull(service2.getDbWarning());
		assertEquals(1, service2.list().size());
		assertEquals(id, service2.list().get(0).id());
		assertEquals("/api", service2.list().get(0).pathContains());
		assertEquals(Map.of("X-Tenant", "t1"), service2.list().get(0).headers());
		assertEquals("ANY", service2.list().get(0).headerMatchMode());
		assertEquals(503, service2.list().get(0).responseStatus());
		service2.shutdown();
	}

	@Test
	void invalidOrInaccessiblePathDoesNotCrashApplicationAndSetsWarning() {
		// Use an impossible path that cannot be created/opened on the OS
		String badPath = "/nonexistent_root_dir_12345/subdir/rules.db";
		SqliteRuleStorage storage = new SqliteRuleStorage(mapper);
		String warning = storage.init(badPath);

		assertNotNull(warning);
		assertFalse(storage.isActive());

		ProxyProperties props = new ProxyProperties("http://localhost:8888", java.time.Duration.ofSeconds(30), badPath);
		RuleServiceImpl service = new RuleServiceImpl(props, storage);
		assertNotNull(service.getDbWarning());
		assertTrue(service.getDbWarning().contains("not synced") || service.getDbWarning().contains("Cannot create"));

		// In-memory functionality still works seamlessly
		String id = service.add(new RuleRequest(null, "GET", "/test", null, 500, null, null, null, null));
		assertEquals(1, service.list().size());
		assertEquals(id, service.list().get(0).id());
		assertTrue(service.findApplicable("GET", "/test").isPresent());
		service.shutdown();
	}

}
