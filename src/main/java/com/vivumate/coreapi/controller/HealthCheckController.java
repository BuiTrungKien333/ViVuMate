package com.vivumate.coreapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthCheckController {

    private final DataSource dataSource;

    // Spring Boot sẽ tự động tiêm (Inject) kết nối Database vào đây
    public HealthCheckController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> checkHealth() {
        try (Connection connection = dataSource.getConnection()) {
            // Nếu dòng này chạy qua mà không lỗi -> DB OK
            boolean isValid = connection.isValid(1000);

            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "database", isValid ? "CONNECTED" : "FAILED", // Báo trạng thái DB
                    "message", "ViVuMate Backend is running!"
            ));
        } catch (SQLException e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "DOWN",
                    "database", "ERROR: " + e.getMessage()
            ));
        }
    }
}