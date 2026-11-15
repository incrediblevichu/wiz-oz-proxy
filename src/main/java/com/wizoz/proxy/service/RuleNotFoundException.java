package com.wizoz.proxy.service;

public class RuleNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RuleNotFoundException(String id) {
		super("There is no rule with id " + id);
	}

}
