package com.vivumate.coreapi.security;

import com.vivumate.coreapi.enums.TokenType;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.service.TokenBlacklistService;
import com.vivumate.coreapi.utils.Translator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    private final Translator translator;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        final String requestUri = request.getRequestURI();
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("[JWT] No Bearer token found, skip authentication. URI={}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);
        if (tokenBlacklistService.isBlacklisted(jwt)) {
            ErrorCode errorCode = ErrorCode.TOKEN_REVOKED;

            response.setStatus(errorCode.getHttpStatus().value());
            response.setContentType("application/json;charset=UTF-8");

            String body = String.format(
                    "{\"code\": %d, \"message\": \"%s\"}",
                    errorCode.getCode(),
                    translator.toLocale(errorCode.getMessageKey())
            );

            response.getWriter().write(body);
            return;
        }

        final String username;
        try {
            username = jwtUtils.extractUsername(jwt, TokenType.ACCESS_TOKEN);
            log.debug("[JWT] Token parsed successfully. Username={}, URI={}", username, requestUri);
        } catch (Exception e) {
            log.warn("[JWT] Invalid or expired token. URI={}, reason={}", requestUri, e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            log.debug("[JWT] SecurityContext already authenticated, skip. URI={}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (jwtUtils.isTokenValid(jwt, userDetails, TokenType.ACCESS_TOKEN)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.info("[JWT] Authentication success. Username={}, URI={}", username, requestUri);
            } else {
                log.warn("[JWT] Token validation failed. Username={}, URI={}", username, requestUri);
            }

        } catch (Exception e) {
            log.error("[JWT] Authentication process failed. Username={}, URI={}", username, requestUri, e);
        }

        filterChain.doFilter(request, response);
    }
}
