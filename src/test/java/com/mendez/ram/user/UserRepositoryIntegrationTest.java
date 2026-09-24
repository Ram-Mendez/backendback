package com.mendez.ram.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mendez.ram.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryIntegrationTest {

	@Autowired
	private UserRepository userRepository;

	@Test
	void savesAndFindsUserByUsername() {
		// ARRANGE — guardar un usuario real en la base de datos de test.
		userRepository.save(new User("local-user"));

		// ASSERT — la búsqueda devuelve el usuario por su nombre.
		assertThat(userRepository.findByUsername("local-user"))
				.isPresent()
				.get()
				.extracting(User::getUsername)
				.isEqualTo("local-user");
	}
}
