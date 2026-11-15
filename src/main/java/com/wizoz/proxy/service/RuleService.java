package com.wizoz.proxy.service;

import java.util.List;
import java.util.Optional;

import com.wizoz.proxy.model.Rule;
import com.wizoz.proxy.model.RuleRequest;
import com.wizoz.proxy.model.RuleView;

/**
 * Holds the rules. Rules are kept in the order they were added and the first enabled rule that matches a request is
 * the one that applies.
 */
public interface RuleService {

	/**
	 * @return the id of the new rule
	 * @throws InvalidRuleException if the rule is invalid
	 */
	String add(RuleRequest request);

	/**
	 * Replaces a rule's settings, keeping its place in the order. Its error pattern starts over.
	 *
	 * @throws InvalidRuleException  if the rule is invalid
	 * @throws RuleNotFoundException if there is no such rule
	 */
	RuleView replace(String id, RuleRequest request);

	/** @throws RuleNotFoundException if there is no such rule */
	RuleView get(String id);

	List<RuleView> list();

	/** @throws RuleNotFoundException if there is no such rule */
	void delete(String id);

	void deleteAll();

	/** @throws RuleNotFoundException if there is no such rule */
	void setEnabled(String id, boolean enabled);

	Optional<Rule> findApplicable(String method, String path, jakarta.servlet.http.HttpServletRequest request);

	Optional<Rule> findApplicable(String method, String path);

	/** While paused, requests are forwarded and no rule applies. */
	void setPaused(boolean paused);

	boolean isPaused();

	String getDbWarning();

	void flushDbSync();

}
