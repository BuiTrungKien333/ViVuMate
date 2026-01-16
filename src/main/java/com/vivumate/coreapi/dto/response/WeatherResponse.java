package com.vivumate.coreapi.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class WeatherResponse {

    @JsonProperty("city_name")
    private String cityName;

    @JsonProperty("country")
    private String country;

    @JsonProperty("temp")
    private double temperature;

    @JsonProperty("feels_like")
    private double feelsLike;

    @JsonProperty("description")
    private String description;

    @JsonProperty("icon")
    private String iconUrl;

    @JsonProperty("humidity")
    private int humidity;

    @JsonProperty("wind_speed")
    private double windSpeed;

    @JsonProperty("visibility")
    private int visibility;

    @JsonProperty("sunrise")
    private String sunriseTime;

    @JsonProperty("sunset")
    private String sunsetTime;

}
