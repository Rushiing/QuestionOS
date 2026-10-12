package com.questionos.backend.service;

import com.questionos.backend.api.dto.AuthDtos;
import com.questionos.backend.persistence.UserAccountJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailClaimServiceTest {
    private final UserAccountJdbcRepository repo = mock(UserAccountJdbcRepository.class);
    private final EmailClaimMailer mailer = mock(EmailClaimMailer.class);
    private final AuthDtos.AuthUser oldUser = new AuthDtos.AuthUser("google_old", "old@example.test", "Old", "");
    private AuthService auth;

    @BeforeEach void setup() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("repo", repo);
        auth = new AuthService("", "sandbox", "test-only-secret", beans.getBeanProvider(UserAccountJdbcRepository.class), mailer);
        when(mailer.available()).thenReturn(true);
    }

    @Test void requestSendsOnlyRawTokenToMailboxAndStoresDigest() throws Exception {
        when(repo.reserveEmailClaim(eq("old@example.test"), anyString())).thenReturn(Optional.of(oldUser));
        auth.requestEmailClaim(" OLD@EXAMPLE.TEST ");
        var token = ArgumentCaptor.forClass(String.class);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(oldUser.email()), token.capture());
        verify(repo).reserveEmailClaim(eq(oldUser.email()), hash.capture());
        assertTrue(token.getValue().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getValue().getBytes(StandardCharsets.UTF_8))), hash.getValue());
        assertNotEquals(token.getValue(), hash.getValue());
    }

    @Test void unknownOrThrottledAccountDoesNotSend() {
        when(repo.reserveEmailClaim(anyString(), anyString())).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> auth.requestEmailClaim("unknown@example.test"));
        verify(mailer, never()).send(anyString(), anyString());
    }

    @Test void disabledMailCannotCreateChallenge() {
        when(mailer.available()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> auth.requestEmailClaim(oldUser.email()));
        verifyNoInteractions(repo);
    }

    @Test void mailFailureInvalidatesChallengeWithoutLeakingDiagnostics() {
        when(repo.reserveEmailClaim(anyString(), anyString())).thenReturn(Optional.of(oldUser));
        doThrow(new MailSendException("SECRET provider diagnostics")).when(mailer).send(anyString(), anyString());
        var error = assertThrows(IllegalStateException.class, () -> auth.requestEmailClaim(oldUser.email()));
        assertFalse(error.toString().contains("SECRET"));
        assertNull(error.getCause());
        verify(repo).invalidateEmailClaim(eq(oldUser.id()), anyString());
    }

    @Test void claimKeepsOldSubjectAndPasswordLoginWorks() {
        when(repo.validEmailClaim(anyString(), anyString())).thenReturn(true);
        when(repo.claimEmail(anyString(), anyString(), anyString())).thenReturn(Optional.of(oldUser));
        var result = auth.claimEmail(" OLD@example.test ", "a".repeat(43), "new-password");
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repo).claimEmail(eq(oldUser.email()), anyString(), hash.capture());
        assertTrue(new BCryptPasswordEncoder().matches("new-password", hash.getValue()));
        assertEquals(oldUser.id(), result.user().id());
        assertEquals(oldUser.id(), auth.verifySessionToken(result.access_token()).id());
        when(repo.findPasswordAccountByEmail(oldUser.email())).thenReturn(Optional.of(
                new UserAccountJdbcRepository.PasswordAccount(oldUser, hash.getValue())));
        assertEquals(oldUser.id(), auth.loginWithPassword(oldUser.email(), "new-password").user().id());
        assertThrows(IllegalArgumentException.class, () -> auth.loginWithPassword(oldUser.email(), "wrong"));
        verify(repo, never()).createPasswordUser(any(), any());
    }

    @Test void invalidTokenAndSandboxCannotClaim() {
        for (var token : new String[]{"sandbox", "invalid", "a".repeat(43)}) {
            assertThrows(IllegalArgumentException.class, () -> auth.claimEmail(oldUser.email(), token, "password"));
        }
        verify(repo, never()).claimEmail(anyString(), anyString(), anyString());
    }

    @Test void registrationCannotForkExistingGoogleIdentity() {
        when(repo.findByEmail(oldUser.email())).thenReturn(Optional.of(oldUser));
        assertThrows(IllegalArgumentException.class, () -> auth.registerWithPassword(oldUser.email(), "password", "new"));
        verify(repo, never()).createPasswordUser(any(), any());
    }

    @Test void oversizedUtf8PasswordIsRejectedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> auth.claimEmail(oldUser.email(), "a".repeat(43), "密".repeat(25)));
        verifyNoInteractions(repo);
    }
}
