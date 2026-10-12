package com.questionos.backend.api;

import com.questionos.backend.api.dto.AuthDtos;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "QOS_AUTH_TEST_JDBC_URL", matches = ".+")
class EmailClaimFlowPostgresTest {
    static JdbcTemplate admin;
    static String schema;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockBean JavaMailSender sender;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("QOS_AUTH_TEST_JDBC_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/qos_auth_test")) {
            throw new IllegalArgumentException("Only dedicated loopback qos_auth_test is allowed");
        }
        String user = System.getenv().getOrDefault("QOS_AUTH_TEST_DB_USER", System.getProperty("user.name"));
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, ""));
        schema = "claim_flow_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String scopedUrl = url + "?currentSchema=" + schema;
        var source = new DriverManagerDataSource(scopedUrl, user, "");
        Flyway.configure().dataSource(source).defaultSchema(schema).target("6")
                .locations("classpath:db/migration").load().migrate();
        var seed = new JdbcTemplate(source);
        seed.update("INSERT INTO qos_user_account(user_id,email,display_name,provider) VALUES ('google_legacy','legacy@example.test','Legacy','google')");
        seed.update("""
                INSERT INTO qos_conversation_session(session_id,owner_user_id,mode,status,created_at,last_activity_at,expires_at)
                VALUES ('legacy-history','google_legacy','CALIBRATION','ACTIVE',now(),now(),now()+interval '1 day')
                """);
        seed.update("""
                INSERT INTO qos_conversation_message(message_id,session_id,turn_id,role,content,created_at)
                VALUES ('legacy-message','legacy-history',1,'USER','Synthetic preserved history',now())
                """);
        properties.add("spring.datasource.url", () -> scopedUrl);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("spring.mail.host", () -> "smtp.example.test");
        properties.add("questionos.auth.claim-mail-from", () -> "QuestionOS <accounts@example.test>");
        properties.add("questionos.auth.jwt.secret", () -> "isolated-test-secret-with-at-least-32-bytes");
        properties.add("questionos.llm.endpoint", () -> "");
        properties.add("questionos.llm.apiKey", () -> "");
        properties.add("server.address", () -> "127.0.0.1");
    }

    @AfterAll static void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void claimLoginAndHistoryUseOriginalIdentityAndRejectOtherUsers() {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        client.post().uri("/api/auth/register").bodyValue(Map.of("email", "legacy@example.test", "password", "test-password"))
                .exchange().expectStatus().isEqualTo(409);
        client.post().uri("/api/auth/email-claim/request").bodyValue(Map.of("email", "legacy@example.test"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.token").doesNotExist();
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertArrayEquals(new String[]{"legacy@example.test"}, mail.getValue().getTo());
        String token = mail.getValue().getText().split("\\n\\n")[1];
        assertEquals(43, token.length());
        assertNotEquals(token, jdbc.queryForObject("SELECT token_hash FROM qos_email_claim", String.class));
        var confirmation = Map.of("email", "legacy@example.test", "token", token, "password", "test-password");
        client.post().uri("/api/auth/email-claim/confirm").bodyValue(confirmation).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.user.id").isEqualTo("google_legacy");
        client.post().uri("/api/auth/email-claim/confirm").bodyValue(confirmation).exchange().expectStatus().isBadRequest();
        client.post().uri("/api/auth/login").bodyValue(Map.of("email", "legacy@example.test", "password", "wrong-password"))
                .exchange().expectStatus().isUnauthorized();
        var login = client.post().uri("/api/auth/login").bodyValue(Map.of("email", "legacy@example.test", "password", "test-password"))
                .exchange().expectStatus().isOk().expectBody(AuthDtos.AuthSuccessResponse.class).returnResult().getResponseBody();
        assertNotNull(login);
        assertEquals("google_legacy", login.user().id());
        client.get().uri("/api/v1/sandbox/sessions").header("Authorization", "Bearer " + login.access_token())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.sessions[0].sessionId").isEqualTo("legacy-history");
        client.get().uri("/api/v1/sandbox/sessions/legacy-history/messages").header("Authorization", "Bearer " + login.access_token())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.messages[0].content").isEqualTo("Synthetic preserved history");
        var other = client.post().uri("/api/auth/register").bodyValue(Map.of("email", "other@example.test", "password", "other-password"))
                .exchange().expectStatus().isOk().expectBody(AuthDtos.AuthSuccessResponse.class).returnResult().getResponseBody();
        assertNotNull(other);
        client.get().uri("/api/v1/sandbox/sessions/legacy-history/messages").header("Authorization", "Bearer " + other.access_token())
                .exchange().expectStatus().isNotFound();
        assertEquals("google", jdbc.queryForObject("SELECT provider FROM qos_user_account WHERE user_id='google_legacy'", String.class));
        assertEquals("google_legacy", jdbc.queryForObject("SELECT owner_user_id FROM qos_conversation_session WHERE session_id='legacy-history'", String.class));
    }
}
