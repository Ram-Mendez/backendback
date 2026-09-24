package com.mendez.ram;

import org.springframework.boot.SpringApplication;

public class TestRamApplication {

	public static void main(String[] args) {
		// Arranca la aplicación con la base de datos de Testcontainers.
		SpringApplication.from(RamApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
