package com.vivumate.coreapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vivumate.coreapi.dto.response.WeatherResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
@Slf4j(topic = "WEATHER_SERVICE")
@RequiredArgsConstructor
public class WeatherService {

    private final WebClient webClient;

    @Value("${vivumate.weather.api-key}")
    private String apiKey;

    @Cacheable(
            value = "weather",
            key = "#lat + '-' + #lon + '-' + T(org.springframework.context.i18n.LocaleContextHolder).getLocale().language",
            unless = "#result == null"
    )
    public WeatherResponse getCurrentWeather(Double lat, Double lon) {

        String langCode = getLanguageCode();

        log.info("Fetching weather for lat: {}, lon: {}, lang: {}", lat, lon, langCode);

        try {
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.openweathermap.org")
                            .path("/data/2.5/weather")
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("appid", apiKey)
                            .queryParam("units", "metric")
                            .queryParam("lang", langCode)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return mapJsonToDto(root);
        } catch (WebClientResponseException e) {
            log.error("OpenWeatherMap API Error: {}", e.getResponseBodyAsString());
            throw new AppException(ErrorCode.WEATHER_API_ERROR);

        } catch (Exception e) {
            log.error("Internal Weather Error", e);
            throw new AppException(ErrorCode.WEATHER_DATA_PARSE_ERROR);
        }
    }

    public String getLanguageCode() {
        Locale currentLocale = LocaleContextHolder.getLocale();
        String lang = currentLocale.getLanguage();

        if (!"vi".equals(lang)) {
            return "en";
        }
        return lang;
    }

    private WeatherResponse mapJsonToDto(JsonNode root) {
        if (root == null)
            return null;

        return WeatherResponse.builder()
                .cityName(safeGetText(root, "name"))
                .country(safeGetText(root.path("sys"), "country"))
                .temperature(root.path("main").path("temp").asDouble())
                .feelsLike(root.path("main").path("feels_like").asDouble())
                .humidity(root.path("main").path("humidity").asInt())
                .windSpeed(root.path("wind").path("speed").asDouble())
                .visibility(root.path("visibility").asInt())
                .description(root.path("weather").get(0).path("description").asText())
                .iconUrl("https://openweathermap.org/img/wn/" +
                        root.path("weather").get(0).path("icon").asText() + "@4x.png") // @4x cho icon nét hơn
                .sunriseTime(convertUnixTime(root.path("sys").path("sunrise").asLong()))
                .sunsetTime(convertUnixTime(root.path("sys").path("sunset").asLong()))
                .build();
    }

    private String convertUnixTime(long unixSeconds) {
        return Instant.ofEpochSecond(unixSeconds)
                .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String safeGetText(JsonNode node, String fieldName) {
        return node.has(fieldName) ? node.get(fieldName).asText() : "N/A";
    }

}
