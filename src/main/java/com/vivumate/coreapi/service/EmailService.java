package com.vivumate.coreapi.service;

public interface EmailService {
    void sendResetPasswordEmail(String to, String fullName, String resetLink);

    void sendVerificationEmail(String to, String fullName, String verifyLink);
}
