package com.questionos.backend.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailClaimMailer {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    private final String host;

    public EmailClaimMailer(ObjectProvider<JavaMailSender> senders,
                            @Value("${questionos.auth.claim-mail-from:}") String from,
                            @Value("${spring.mail.host:}") String host) {
        this.senders = senders;
        this.from = from;
        this.host = host;
    }

    public boolean available() {
        return !host.isBlank() && !from.isBlank() && senders.getIfAvailable() != null;
    }

    public void send(String email, String token) {
        var sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) throw new IllegalStateException("邮箱验证暂不可用，请稍后重试");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("QuestionOS · 恢复旧账号");
        message.setText("请在 QuestionOS 的找回旧账号页面粘贴以下验证口令，然后设置密码。口令 10 分钟内有效，仅可使用一次。\n\n"
                + token + "\n\n验证后保留原账号和历史记录。如果不是你本人发起，请忽略此邮件，不要向他人提供口令。");
        sender.send(message);
    }
}
