package com.questionos.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailClaimMailerTest {
    @Test void messageOnlyGoesToVerifiedAccountAddress() {
        var sender = mock(JavaMailSender.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("mail", sender);
        var mailer = new EmailClaimMailer(beans.getBeanProvider(JavaMailSender.class), "accounts@example.test", "smtp.example.test");
        assertTrue(mailer.available());
        mailer.send("old@example.test", "test-onetime-token");
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertArrayEquals(new String[]{"old@example.test"}, message.getValue().getTo());
        assertEquals("accounts@example.test", message.getValue().getFrom());
        assertTrue(message.getValue().getText().contains("test-onetime-token"));
        assertTrue(message.getValue().getText().contains("10 分钟"));
    }

    @Test void missingConfigurationIsUnavailable() {
        var beans = new StaticListableBeanFactory();
        var mailer = new EmailClaimMailer(beans.getBeanProvider(JavaMailSender.class), "", "");
        assertFalse(mailer.available());
        assertThrows(IllegalStateException.class, () -> mailer.send("old@example.test", "token"));
    }
}
