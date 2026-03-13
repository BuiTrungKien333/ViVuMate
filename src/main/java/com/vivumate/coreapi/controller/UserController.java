package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j(topic = "USER_CONTROLLER")
@Tag(name = "User Management", description = "User information management")
public class UserController {

    private final UserService userService;

}
