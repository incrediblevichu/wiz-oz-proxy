package com.wizoz.proxy.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wizoz.proxy.config.ProxyProperties;
import com.wizoz.proxy.model.ErrorPattern;
import com.wizoz.proxy.model.Rule;
import com.wizoz.proxy.model.RuleRequest;
import com.wizoz.proxy.model.RuleView;
import com.wizoz.proxy.service.InvalidRuleException;
import com.wizoz.proxy.service.RuleNotFoundException;
import com.wizoz.proxy.service.RuleService;
import com.wizoz.proxy.service.impl.SqliteRuleStorage.PersistedRule;

import jakarta.annotation.PreDestroy;

@Service
public class RuleServiceImpl implements RuleService {

	private static final Logger LOGGER = LoggerFactory.getLogger(RuleServiceImpl.class);

	private final List<Rule> rules = new CopyOnWriteArrayList<>();
	private final SqliteRuleStorage storage;
	private final BlockingQueue<List<PersistedRule>> syncQueue = new LinkedBlockingQueue<>();
	private final ExecutorService syncExecutor;
	private volatile boolean paused;
	private volatile String dbWarning;

	@org.springframework.beans.factory.annotation.Autowired
	public RuleServiceImpl(ProxyProperties properties, SqliteRuleStorage storage) {
		this.storage = storage;
		this.syncExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "wizoz-db-sync");
			t.setDaemon(true);
			return t;
		});

		this.dbWarning = storage.init(properties.databasePath());
		if (this.dbWarning == null) {
			try {
				List<PersistedRule> loaded = storage.loadAll();
				for (PersistedRule pr : loaded) {
					rules.add(new Rule(pr.id(), pr.spec(), ErrorPattern.parse(pr.spec().errorPattern())));
				}
				LOGGER.info("Loaded {} rules from database", rules.size());
			} catch (Exception e) {
				this.dbWarning = "Failed to load rules from database: " + e.getMessage();
				LOGGER.warn(this.dbWarning, e);
			}
		}

		startSyncWorker();
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(this::flushDirect, "wizoz-jvm-shutdown-db-flush"));
		} catch (Exception e) {
			LOGGER.debug("Could not register JVM shutdown hook: {}", e.getMessage());
		}
	}

	/** Constructor for tests or default in-memory storage. */
	public RuleServiceImpl() {
		this(new ProxyProperties("http://localhost:8888", java.time.Duration.ofSeconds(30), ":memory:"),
				new SqliteRuleStorage(new ObjectMapper()));
	}

	private void startSyncWorker() {
		syncExecutor.submit(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					List<PersistedRule> snapshot = syncQueue.take();
					while (!syncQueue.isEmpty()) {
						List<PersistedRule> next = syncQueue.poll();
						if (next != null) {
							snapshot = next;
						}
					}
					storage.saveAll(snapshot);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (Exception e) {
					LOGGER.warn("Failed to synchronize rules to SQLite database: {}", e.getMessage());
					this.dbWarning = "Rules are not synced to the database (write error: " + e.getMessage() + ")";
				}
			}
		});
	}

	private void enqueueSync() {
		if (storage.isActive()) {
			List<PersistedRule> snapshot = new ArrayList<>();
			for (Rule rule : rules) {
				snapshot.add(new PersistedRule(rule.id(), rule.spec()));
			}
			syncQueue.offer(snapshot);
		}
	}

	@Override
	public synchronized String add(RuleRequest request) {
		Rule rule = build(UUID.randomUUID().toString(), request);
		rules.add(rule);
		enqueueSync();
		return rule.id();
	}

	@Override
	public synchronized RuleView replace(String id, RuleRequest request) {
		Rule replacement = build(id, request);
		rules.set(indexOf(id), replacement);
		enqueueSync();
		return replacement.view();
	}

	@Override
	public RuleView get(String id) {
		return find(id).view();
	}

	@Override
	public List<RuleView> list() {
		return rules.stream().map(Rule::view).toList();
	}

	@Override
	public synchronized void delete(String id) {
		if (!rules.removeIf(rule -> rule.id().equals(id))) {
			throw new RuleNotFoundException(id);
		}
		enqueueSync();
	}

	@Override
	public synchronized void deleteAll() {
		rules.clear();
		enqueueSync();
	}

	@Override
	public void setEnabled(String id, boolean enabled) {
		find(id).setEnabled(enabled);
		enqueueSync();
	}

	@Override
	public Optional<Rule> findApplicable(String method, String path) {
		return findApplicable(method, path, null);
	}

	@Override
	public Optional<Rule> findApplicable(String method, String path, jakarta.servlet.http.HttpServletRequest request) {
		if (paused) {
			return Optional.empty();
		}
		return rules.stream().filter(rule -> rule.isEnabled() && rule.matches(method, path, request)).findFirst();
	}

	@Override
	public void setPaused(boolean paused) {
		this.paused = paused;
	}

	@Override
	public boolean isPaused() {
		return paused;
	}

	@Override
	public String getDbWarning() {
		return dbWarning;
	}

	@Override
	public void flushDbSync() {
		flushDirect();
	}

	public synchronized void flushDirect() {
		if (!storage.isActive()) {
			return;
		}
		List<PersistedRule> snapshot = new ArrayList<>();
		for (Rule rule : rules) {
			snapshot.add(new PersistedRule(rule.id(), rule.spec()));
		}
		try {
			storage.saveAll(snapshot);
			syncQueue.clear();
			LOGGER.info("Flushed {} rules to SQLite database", snapshot.size());
		} catch (Exception e) {
			LOGGER.warn("Failed to flush rules to SQLite database on shutdown: {}", e.getMessage());
		}
	}

	@PreDestroy
	public void shutdown() {
		flushDirect();
		syncExecutor.shutdown();
		try {
			syncExecutor.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Rule find(String id) {
		return rules.stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow(() -> new RuleNotFoundException(id));
	}

	private int indexOf(String id) {
		for (int i = 0; i < rules.size(); i++) {
			if (rules.get(i).id().equals(id)) {
				return i;
			}
		}
		throw new RuleNotFoundException(id);
	}

	private static Rule build(String id, RuleRequest request) {
		if (request == null) {
			throw new InvalidRuleException("A rule is required");
		}
		try {
			RuleRequest spec = request.normalized();
			return new Rule(id, spec, ErrorPattern.parse(spec.errorPattern()));
		} catch (IllegalArgumentException e) {
			throw new InvalidRuleException(e.getMessage());
		}
	}

}
