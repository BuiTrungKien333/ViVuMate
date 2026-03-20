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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "EMAIL_SERVICE")
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Override
    @Async
    public void sendResetPasswordEmail(String to, String fullName, String resetLink) {
        log.info("(Attempt) Sending reset password email to: {}", to);

        Map<String, Object> variables = Map.of(
                "name", fullName,
                "resetLink", resetLink
        );

        sendHtmlEmail(to, "[ViVuMate] Yêu cầu khôi phục mật khẩu", "reset-password", variables);
    }

    @Override
    @Async
    public void sendVerificationEmail(String to, String fullName, String verifyLink) {
        log.info("(Attempt) Sending verify email to: {}", to);

        Map<String, Object> variables = Map.of(
                "name", fullName,
                "verifyLink", verifyLink
        );

        sendHtmlEmail(to, "[ViVuMate] Kích hoạt tài khoản ViVuMate của bạn", "verify-email", variables);
    }

    @Override
    @Async
    public void sendLoginOtpEmail(String to, String fullName, String otp) {
        log.info("(Attempt) Sending login OTP email to: {}", to);

        Map<String, Object> variables = Map.of(
                "name", fullName,
                "otp", otp
        );

        sendHtmlEmail(to, "[ViVuMate] Mã xác thực đăng nhập", "login-otp", variables);
    }

    /**
     * Private helper: render Thymeleaf template + gửi MimeMessage.
     */
    private void sendHtmlEmail(String to, String subject, String templateName, Map<String, Object> variables) {
        try {
            Context context = new Context();
            variables.forEach(context::setVariable);

            String htmlContent = templateEngine.process(templateName, context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom("onboarding@resend.dev", "ViVuMate Security");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("(Success) Email '{}' sent to: {}", subject, to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("(Failed) Could not send email '{}' to: {}. Error: {}", subject, to, e.getMessage());
        }
    }
}
