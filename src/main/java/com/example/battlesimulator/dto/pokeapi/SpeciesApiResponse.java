package com.example.battlesimulator.dto.pokeapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpeciesApiResponse(
        int gender_rate
) {
}
