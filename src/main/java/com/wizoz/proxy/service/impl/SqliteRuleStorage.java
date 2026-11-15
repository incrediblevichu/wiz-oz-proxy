package com.wizoz.proxy.service.impl;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wizoz.proxy.model.RuleRequest;

@Component
public class SqliteRuleStorage {

	private static final Logger LOGGER = LoggerFactory.getLogger(SqliteRuleStorage.class);

	public record PersistedRule(String id, RuleRequest spec) {
	}

	private final ObjectMapper objectMapper;
	private String jdbcUrl;
	private volatile boolean active;
	private Connection keepAliveConnection;

	public SqliteRuleStorage() {
		this(new ObjectMapper());
	}

	public SqliteRuleStorage(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
	}

	/**
	 * Initializes the database.
	 *
	 * @param dbPath path to the sqlite db file or :memory:
	 * @return warning message if database could not be initialized, or null on success
	 */
	public synchronized String init(String dbPath) {
		if (dbPath == null || dbPath.isBlank()) {
			dbPath = "rules.db";
		}
		if (":memory:".equals(dbPath)) {
			this.jdbcUrl = "jdbc:sqlite:file:wizoz_rules_mem_" + java.util.UUID.randomUUID() + "?mode=memory&cache=shared";
		} else {
			File file = new File(dbPath);
			File parent = file.getParentFile();
			if (parent != null && !parent.exists() && !parent.mkdirs()) {
				String warning = "Cannot create database directory for " + dbPath;
				LOGGER.warn(warning);
				this.active = false;
				return warning;
			}
			try {
				if (!file.exists()) {
					file.createNewFile();
				}
			} catch (Exception e) {
				String warning = "Rules are not synced to the database (permission or storage error: " + e.getMessage() + ")";
				LOGGER.warn("Failed to create SQLite database file at {}: {}. Rules will be stored in-memory only.", dbPath, e.getMessage());
				this.active = false;
				return warning;
			}
			this.jdbcUrl = "jdbc:sqlite:" + file.getAbsolutePath();
		}

		try {
			if (":memory:".equals(dbPath)) {
				this.keepAliveConnection = DriverManager.getConnection(this.jdbcUrl);
			}
			try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
				stmt.execute("""
						CREATE TABLE IF NOT EXISTS rules (
							id TEXT PRIMARY KEY,
							position INTEGER NOT NULL,
							enabled INTEGER NOT NULL,
							method TEXT,
							path_contains TEXT,
							headers_json TEXT,
							header_match_mode TEXT,
							query_params_json TEXT,
							query_param_match_mode TEXT,
							delay_ms INTEGER,
							response_status INTEGER,
							response_body TEXT,
							content_type TEXT,
							error_probability INTEGER,
							error_pattern TEXT
						)
						""");
			}
			this.active = true;
			LOGGER.info("Initialized rules database at {}", dbPath);
			return null;
		} catch (Exception e) {
			String warning = "Rules are not synced to the database (permission or storage error: " + e.getMessage() + ")";
			LOGGER.warn("Failed to initialize SQLite database at {}: {}. Rules will be stored in-memory only.", dbPath, e.getMessage());
			this.active = false;
			return warning;
		}
	}

	public boolean isActive() {
		return active;
	}

	public List<PersistedRule> loadAll() throws SQLException {
		if (!active) {
			return List.of();
		}
		List<PersistedRule> result = new ArrayList<>();
		String sql = """
				SELECT id, enabled, method, path_contains, headers_json, header_match_mode, query_params_json,
				       query_param_match_mode, delay_ms, response_status, response_body, content_type,
				       error_probability, error_pattern
				FROM rules ORDER BY position ASC
				""";
		try (Connection conn = getConnection();
				PreparedStatement ps = conn.prepareStatement(sql);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				String id = rs.getString("id");
				boolean enabled = rs.getInt("enabled") == 1;
				String method = rs.getString("method");
				String pathContains = rs.getString("path_contains");
				String headersJson = rs.getString("headers_json");
				Map<String, String> headers = Map.of();
				if (headersJson != null && !headersJson.isBlank()) {
					try {
						headers = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
					} catch (Exception e) {
						LOGGER.warn("Could not deserialize headers JSON for rule {}: {}", id, e.getMessage());
					}
				}
				String headerMatchMode = rs.getString("header_match_mode");
				String queryParamsJson = rs.getString("query_params_json");
				Map<String, String> queryParams = Map.of();
				if (queryParamsJson != null && !queryParamsJson.isBlank()) {
					try {
						queryParams = objectMapper.readValue(queryParamsJson, new TypeReference<Map<String, String>>() {});
					} catch (Exception e) {
						LOGGER.warn("Could not deserialize queryParams JSON for rule {}: {}", id, e.getMessage());
					}
				}
				String queryParamMatchMode = rs.getString("query_param_match_mode");
				int delayMs = rs.getInt("delay_ms");
				int statusVal = rs.getInt("response_status");
				Integer responseStatus = rs.wasNull() ? null : statusVal;
				String responseBody = rs.getString("response_body");
				String contentType = rs.getString("content_type");
				int probVal = rs.getInt("error_probability");
				Integer errorProbability = rs.wasNull() ? null : probVal;
				String errorPattern = rs.getString("error_pattern");

				RuleRequest req = new RuleRequest(enabled, method, pathContains, headers, headerMatchMode, queryParams,
						queryParamMatchMode, null, null, null, null, delayMs, responseStatus, responseBody, contentType,
						errorProbability, errorPattern).normalized();
				result.add(new PersistedRule(id, req));
			}
		}
		return result;
	}

	public void saveAll(List<PersistedRule> rules) throws Exception {
		if (!active) {
			return;
		}
		try (Connection conn = getConnection()) {
			conn.setAutoCommit(false);
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("DELETE FROM rules");
			}
			String sql = """
					INSERT INTO rules (id, position, enabled, method, path_contains, headers_json, header_match_mode,
					                   query_params_json, query_param_match_mode, delay_ms, response_status,
					                   response_body, content_type, error_probability, error_pattern)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				for (int i = 0; i < rules.size(); i++) {
					PersistedRule pr = rules.get(i);
					RuleRequest r = pr.spec();
					ps.setString(1, pr.id());
					ps.setInt(2, i);
					ps.setInt(3, r.enabled() ? 1 : 0);
					ps.setString(4, r.method());
					ps.setString(5, r.pathContains());
					ps.setString(6, objectMapper.writeValueAsString(r.headers()));
					ps.setString(7, r.headerMatchMode());
					ps.setString(8, objectMapper.writeValueAsString(r.queryParams()));
					ps.setString(9, r.queryParamMatchMode());
					ps.setInt(10, r.delayMs());
					if (r.responseStatus() == null) {
						ps.setNull(11, Types.INTEGER);
					} else {
						ps.setInt(11, r.responseStatus());
					}
					ps.setString(12, r.responseBody());
					ps.setString(13, r.contentType());
					if (r.errorProbability() == null) {
						ps.setNull(14, Types.INTEGER);
					} else {
						ps.setInt(14, r.errorProbability());
					}
					ps.setString(15, r.errorPattern());
					ps.addBatch();
				}
				ps.executeBatch();
			}
			conn.commit();
		}
	}

	private Connection getConnection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl);
	}

}
