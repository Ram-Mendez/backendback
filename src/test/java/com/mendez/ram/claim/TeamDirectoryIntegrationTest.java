package com.mendez.ram.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.mendez.ram.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "ram.sla.scheduling-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamDirectoryIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void enabledTeamIsListedImmediatelyBeforeAnyMemberIsAdded() throws Exception {
        String login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@local.dev\",\"password\":\"DevAdmin123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String authorization = "Bearer " + objectMapper.readTree(login).get("accessToken").asText();
        String name = "Empty team " + UUID.randomUUID();
        String created = mockMvc.perform(post("/api/v1/teams")
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();
        String directory = mockMvc.perform(get("/api/v1/teams")
                .header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        boolean found = false;
        for (var team : objectMapper.readTree(directory)) {
            if (team.get("id").asLong() == id) {
                assertThat(team.get("name").asText()).isEqualTo(name);
                assertThat(team.get("enabled").asBoolean()).isTrue();
                found = true;
            }
        }
        assertThat(found).isTrue();
        mockMvc.perform(get("/api/v1/teams/" + id + "/members")
                .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(objectMapper.readTree(
                        result.getResponse().getContentAsString()).size()).isZero());
    }
}
