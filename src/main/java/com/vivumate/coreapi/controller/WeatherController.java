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
@Tag(name = "WEATHER_CONTROLLER")
@Tag(
        name = "Weather",
        description = "APIs for retrieving real-time weather information based on geographic coordinates."
)
public class WeatherController {

    private final WeatherService weatherService;
    private final Translator translator;

    @Operation(
            summary = "Get Current Weather",
            description = "Retrieve the current weather data using latitude and longitude coordinates. " +
                    "This endpoint returns temperature, city name, and other related weather information."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Weather information retrieved successfully"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid latitude or longitude value"
    )
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
                .message(translator.toLocale("success.weather.get"))
                .data(data)
                .build();
    }
}
