package com.wizoz.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WizOzProxyApplication {

	public static void main(String[] args) {
		SpringApplication.run(WizOzProxyApplication.class, args);
	}

}
