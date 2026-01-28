package com.vivumate.coreapi.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class TestController {

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    public String dashboard() {
        return "Can view Dashboard Admin";
    }

}
