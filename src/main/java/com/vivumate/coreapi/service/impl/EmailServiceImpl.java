package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.UnsupportedEncodingException;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "EMAIL_SERVICE")
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Override
    @Async
    public void sendResetPasswordEmail(String to, String fullName, String resetLink) {
        to = "buitrungkien2005qng@gmail.com";
        log.info("(Attempt) Sending reset password email to: {}", to);

        try {
            Context context = new Context();
            context.setVariable("name", fullName);
            context.setVariable("resetLink", resetLink);

            String htmlContent = templateEngine.process("reset-password", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom("onboarding@resend.dev", "ViVuMate Security");
            helper.setTo(to);
            helper.setSubject("[ViVuMate] Yêu cầu khôi phục mật khẩu");
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("(Success) Reset password email sent to: {}", to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("(Failed) Could not send reset password email to: {}. Error: {}", to, e.getMessage());
        }
    }

    @Override
    @Async
    public void sendVerificationEmail(String to, String fullName, String verifyLink) {
        to = "buitrungkien2005qng@gmail.com";
        log.info("(Attempt) Sending verify email to: {}", to);

        try {
            Context context = new Context();
            context.setVariable("name", fullName);
            context.setVariable("verifyLink", verifyLink);

            String htmlContent = templateEngine.process("verify-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom("onboarding@resend.dev", "ViVuMate Security");
            helper.setTo(to);
            helper.setSubject("[ViVuMate] Kích hoạt tài khoản ViVuMate của bạn");
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("(Success) Verify email sent to: {}", to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("(Failed) Could not send verify email to: {}. Error: {}", to, e.getMessage());
        }
    }
}
