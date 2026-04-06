package com.example.battlesimulator.dto;

import com.example.battlesimulator.model.StatBlock;
import com.example.battlesimulator.model.enums.Ability;
import com.example.battlesimulator.model.enums.HeldItem;
import com.example.battlesimulator.model.enums.Nature;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.List;

public record BattleInitRequest(
        String battleId,
        String playerId,
        boolean aiControlled,
        int pokemonSlot,
        String speciesName,
        int level,
        StatBlock ivs,
        StatBlock evs,
        Nature nature,
        List<String> moveNames,
        @JsonSetter(nulls = Nulls.SKIP) HeldItem heldItem,
        @JsonSetter(nulls = Nulls.SKIP) Ability ability
) {
    // Compact canonical constructor — coerce nulls to defaults
    public BattleInitRequest {
        if (heldItem == null) heldItem = HeldItem.NONE;
        if (ability  == null) ability  = Ability.NONE;
    }
}
