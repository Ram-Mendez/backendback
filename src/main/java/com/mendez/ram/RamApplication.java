package com.mendez.ram;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RamApplication {

	public static void main(String[] args) {
		SpringApplication.run(RamApplication.class, args);
	}

}
