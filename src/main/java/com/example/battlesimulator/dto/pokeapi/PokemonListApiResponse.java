package com.example.battlesimulator.dto.pokeapi;

import java.util.List;

public record PokemonListApiResponse(
        List<PokemonListEntry> results
) {
    public record PokemonListEntry(String name) {}
}
