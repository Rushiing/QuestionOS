package com.questionos.backend.persistence;

import com.questionos.backend.api.dto.AuthDtos;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QOS_AUTH_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailClaimPostgresTest {
    static DataSource source;
    private AnnotationConfigApplicationContext context;
    private UserAccountJdbcRepository repo;
    private JdbcTemplate jdbc;
    private String schema;
    private JdbcTemplate admin;
    private final AuthDtos.AuthUser user = new AuthDtos.AuthUser("google_old", "old@example.test", "Original", "avatar");

    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource source() { return source; }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean UserAccountJdbcRepository repository(DataSource source) { return new UserAccountJdbcRepository(source); }
    }

    @BeforeAll void database() {
        String url = System.getenv("QOS_AUTH_TEST_JDBC_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/qos_auth_test")) {
            throw new IllegalArgumentException("Only a dedicated loopback qos_auth_test database is allowed");
        }
        String dbUser = System.getenv().getOrDefault("QOS_AUTH_TEST_DB_USER", System.getProperty("user.name"));
        admin = new JdbcTemplate(new DriverManagerDataSource(url, dbUser, ""));
        schema = "claim_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema, dbUser, "");
        Flyway.configure().dataSource(source).defaultSchema(schema).target("6")
                .locations("classpath:db/migration").load().migrate();
        var beforeMigration = new UserAccountJdbcRepository(source);
        beforeMigration.upsertGoogleUser(user);
        beforeMigration.createPasswordUser(new AuthDtos.AuthUser("password_existing", "password@example.test", "Existing", ""), "existing-hash");
        Flyway.configure().dataSource(source).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        assertEquals(user, beforeMigration.findByUserId(user.id()).orElseThrow());
        assertEquals("existing-hash", beforeMigration.findPasswordAccountByEmail("password@example.test").orElseThrow().passwordHash());
        context = new AnnotationConfigApplicationContext(Config.class);
        repo = context.getBean(UserAccountJdbcRepository.class);
        jdbc = new JdbcTemplate(source);
    }

    @AfterAll void cleanup() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE qos_email_claim, qos_user_account, qos_conversation_session CASCADE");
        jdbc.update("UPDATE qos_email_claim_budget SET window_start = now(), send_count = 0");
        repo.upsertGoogleUser(user);
        jdbc.update("""
                INSERT INTO qos_conversation_session(session_id,owner_user_id,mode,status,created_at,last_activity_at,expires_at)
                VALUES ('old-session', ?, 'CALIBRATION', 'ACTIVE', now(), now(), now()+interval '1 day')
                """, user.id());
    }

    @Test void claimPreservesHistoryProviderAndGoogleUpsertPreservesPassword() {
        assertTrue(repo.reserveEmailClaim(user.email(), "hash").isPresent());
        assertTrue(repo.validEmailClaim(user.email(), "hash"));
        assertEquals(user.id(), repo.claimEmail(user.email(), "hash", "bcrypt-fixture").orElseThrow().id());
        assertFalse(repo.validEmailClaim(user.email(), "hash"));
        assertTrue(repo.claimEmail(user.email(), "hash", "replace").isEmpty());
        repo.upsertGoogleUser(user);
        assertEquals("bcrypt-fixture", repo.findPasswordAccountByEmail(user.email()).orElseThrow().passwordHash());
        assertEquals("google", jdbc.queryForObject("SELECT provider FROM qos_user_account", String.class));
        assertEquals(user.id(), jdbc.queryForObject("SELECT owner_user_id FROM qos_conversation_session", String.class));
        assertTrue(repo.reserveEmailClaim(user.email(), "new").isEmpty());
    }

    @Test void expiryWrongEmailWrongTokenAndEmailChangeFailClosed() {
        repo.reserveEmailClaim(user.email(), "hash");
        assertTrue(repo.claimEmail("other@example.test", "hash", "pw").isEmpty());
        assertTrue(repo.claimEmail(user.email(), "wrong", "pw").isEmpty());
        jdbc.update("UPDATE qos_email_claim SET expires_at = now() - interval '1 second'");
        assertTrue(repo.claimEmail(user.email(), "hash", "pw").isEmpty());
        jdbc.update("UPDATE qos_email_claim SET expires_at = now() + interval '1 minute'");
        repo.upsertGoogleUser(new AuthDtos.AuthUser(user.id(), "changed@example.test", user.name(), ""));
        assertTrue(repo.claimEmail(user.email(), "hash", "pw").isEmpty());
        assertTrue(repo.claimEmail("changed@example.test", "hash", "pw").isEmpty());
    }

    @Test void resendCooldownHourlyLimitAndOldTokenInvalidation() {
        assertTrue(repo.reserveEmailClaim(user.email(), "one").isPresent());
        assertTrue(repo.reserveEmailClaim(user.email(), "two").isEmpty());
        for (int i = 2; i <= 5; i++) {
            jdbc.update("UPDATE qos_email_claim SET sent_at = now() - interval '61 seconds'");
            assertTrue(repo.reserveEmailClaim(user.email(), "token" + i).isPresent());
        }
        assertFalse(repo.validEmailClaim(user.email(), "one"));
        jdbc.update("UPDATE qos_email_claim SET sent_at = now() - interval '61 seconds'");
        assertTrue(repo.reserveEmailClaim(user.email(), "six").isEmpty());
        jdbc.update("UPDATE qos_email_claim SET window_start = now() - interval '61 minutes'");
        assertTrue(repo.reserveEmailClaim(user.email(), "next-hour").isPresent());
    }

    @Test void globalBudgetAndMailFailureLeaveNoUsableToken() {
        jdbc.update("UPDATE qos_email_claim_budget SET send_count = 30");
        assertTrue(repo.reserveEmailClaim(user.email(), "hash").isEmpty());
        assertFalse(repo.validEmailClaim(user.email(), "hash"));
        jdbc.update("UPDATE qos_email_claim_budget SET window_start = now() - interval '61 minutes'");
        jdbc.update("UPDATE qos_email_claim SET sent_at = now() - interval '61 seconds'");
        assertTrue(repo.reserveEmailClaim(user.email(), "new").isPresent());
        repo.invalidateEmailClaim(user.id(), "old");
        assertTrue(repo.validEmailClaim(user.email(), "new"));
        repo.invalidateEmailClaim(user.id(), "new");
        assertFalse(repo.validEmailClaim(user.email(), "new"));
    }

    @Test void simultaneousClaimsHaveExactlyOneWinner() throws Exception {
        repo.reserveEmailClaim(user.email(), "hash");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> repo.claimEmail(user.email(), "hash", "one").isPresent());
            var b = executor.submit(() -> repo.claimEmail(user.email(), "hash", "two").isPresent());
            assertNotEquals(a.get(), b.get());
        }
    }

    @Test void failedPasswordWriteRollsBackTokenConsumption() {
        repo.reserveEmailClaim(user.email(), "hash");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> repo.claimEmail(user.email(), "hash", "x".repeat(101)));
        assertTrue(repo.validEmailClaim(user.email(), "hash"));
        assertTrue(repo.findPasswordAccountByEmail(user.email()).isEmpty());
    }

    @Test void normalizedEmailUniquenessProtectsRegistrationRace() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> repo.createPasswordUser(
                new AuthDtos.AuthUser("password_new", " OLD@EXAMPLE.TEST ", "New", ""), "hash"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM qos_user_account", Integer.class));
    }
}
