package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.WeatherResponse;
import com.vivumate.coreapi.service.WeatherService;
import com.vivumate.coreapi.utils.Translator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/weather")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Weather Controller")
public class WeatherController {

    private final WeatherService weatherService;

    @Operation(summary = "Get current weather", description = "API to retrieve current weather information by latitude and longitude")
    @GetMapping("/current")
    public ApiResponse<WeatherResponse> getWeather(@RequestParam("lat")
                                                   @NotNull(message = "{weather.latitude.notNull}")
                                                   @DecimalMin(value = "-90.0", message = "{weather.latitude.min}")
                                                   @DecimalMax(value = "90.0", message = "{weather.latitude.max}")
                                                   Double lat,

                                                   @RequestParam("lon")
                                                   @NotNull(message = "{weather.longitude.notNull}")
                                                   @DecimalMin(value = "-180.0", message = "{weather.longitude.min}")
                                                   @DecimalMax(value = "180.0", message = "{weather.longitude.max}")
                                                   Double lon) {
        log.info("(Request) Get Weather - Lat: {}, Lon: {}", lat, lon);

        WeatherResponse data = weatherService.getCurrentWeather(lat, lon);

        log.info("(Response) Get Weather Success - City: {}", data.getCityName());

        return ApiResponse.<WeatherResponse>builder()
                .code(200)
                .message(Translator.toLocale("success.weather.get"))
                .data(data)
                .build();
    }
}
