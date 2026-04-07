package com.example.battlesimulator.service;

import com.example.battlesimulator.dto.SpeciesStatsResponse;
import com.example.battlesimulator.dto.pokeapi.MoveApiResponse;
import com.example.battlesimulator.dto.pokeapi.PokemonApiResponse;
import com.example.battlesimulator.dto.pokeapi.PokemonListApiResponse;
import com.example.battlesimulator.model.BattlePokemon;
import com.example.battlesimulator.model.Move;
import com.example.battlesimulator.model.StatBlock;
import com.example.battlesimulator.model.enums.Ability;
import com.example.battlesimulator.model.enums.MoveCategory;
import com.example.battlesimulator.model.enums.Nature;
import com.example.battlesimulator.model.enums.Stat;
import com.example.battlesimulator.model.enums.Type;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class PokeApiService {
    private static final String ASH_PIKACHU = "ash-pikachu";
    private static final String BASE_PIKACHU = "pikachu";
    private static final java.util.Set<String> SUPPORTED_MEGA_SPECIES = java.util.Set.of(
            "venusaur-mega",
            "charizard-mega-x",
            "charizard-mega-y",
            "blastoise-mega",
            "alakazam-mega",
            "gardevoir-mega",
            "mawile-mega",
            "lucario-mega",
            "scizor-mega",
            "tyranitar-mega",
            "garchomp-mega",
            "salamence-mega",
            "sableye-mega",
            "metagross-mega",
            "rayquaza-mega"
    );

    private static final Map<String, String> MOVE_ALIASES = Map.ofEntries(
            Map.entry("aurasphere", "aura-sphere"),
            Map.entry("softboiled", "soft-boiled"),
            Map.entry("doubleedge", "double-edge"),
            Map.entry("solarbeam", "solar-beam"),
            Map.entry("thundershock", "thunder-shock"),
            Map.entry("dynamicpunch", "dynamic-punch"),
            Map.entry("extremespeed", "extreme-speed"),
            Map.entry("feintattack", "feint-attack"),
            Map.entry("highjumpkick", "high-jump-kick"),
            Map.entry("selfdestruct", "self-destruct"),
            Map.entry("smellingsalts", "smelling-salts"),
            Map.entry("tailwhip", "tail-whip"),
            Map.entry("vicegrip", "vise-grip")
    );

    private final RestClient restClient;

    public PokeApiService(RestClient pokeApiClient) {
        this.restClient = pokeApiClient;
    }

    public BattlePokemon createBattlePokemon(String speciesName, int level, StatBlock ivs, StatBlock evs, Nature nature) {
        if (isAshPikachu(speciesName)) {
            return createAshPikachu(level, ivs, evs, nature);
        }

        PokemonApiResponse apiResponse = fetchPokemon(speciesName);

        if (apiResponse == null) throw new IllegalArgumentException("Pokemon not found: " + speciesName);

        Type type1 = Type.valueOf(apiResponse.types().get(0).type().name().toUpperCase());
        Type type2 = apiResponse.types().size() > 1
                ? Type.valueOf(apiResponse.types().get(1).type().name().toUpperCase()) : Type.NONE;

        int baseHp  = getBaseStat(apiResponse, "hp");
        int baseAtk = getBaseStat(apiResponse, "attack");
        int baseDef = getBaseStat(apiResponse, "defense");
        int baseSpA = getBaseStat(apiResponse, "special-attack");
        int baseSpD = getBaseStat(apiResponse, "special-defense");
        int baseSpe = getBaseStat(apiResponse, "speed");

        int finalHp = calculateHpStat(baseHp, ivs.hp(), evs.hp(), level);

        return BattlePokemon.builder()
                .speciesId(apiResponse.name())
                .originalSpeciesId(apiResponse.name())
                .nickname(apiResponse.name())
                .level(level)
                .nature(nature)
                .ivs(ivs)
                .evs(evs)
                .type1(type1)
                .type2(type2)
                .baseType1(type1)
                .baseType2(type2)
                .maxHp(finalHp)
                .currentHp(finalHp)
                .attack(calculateOtherStat(baseAtk, ivs.attack(), evs.attack(), level, nature, Stat.ATTACK))
                .defense(calculateOtherStat(baseDef, ivs.defense(), evs.defense(), level, nature, Stat.DEFENSE))
                .specialAttack(calculateOtherStat(baseSpA, ivs.specialAttack(), evs.specialAttack(), level, nature, Stat.SPECIAL_ATTACK))
                .specialDefense(calculateOtherStat(baseSpD, ivs.specialDefense(), evs.specialDefense(), level, nature, Stat.SPECIAL_DEFENSE))
                .speed(calculateOtherStat(baseSpe, ivs.speed(), evs.speed(), level, nature, Stat.SPEED))
                .moves(new ArrayList<>())
                .build();
    }

    @Cacheable("pokemonAbilityOptions")
    public List<String> getValidAbilities(String speciesName) {
        if (isAshPikachu(speciesName)) {
            return getValidAbilities(BASE_PIKACHU);
        }

        PokemonApiResponse apiResponse = fetchPokemon(speciesName);

        if (apiResponse == null) {
            throw new IllegalArgumentException("Pokemon not found: " + speciesName);
        }

        List<String> validAbilities = apiResponse.abilities().stream()
                .map(PokemonApiResponse.AbilitySlot::ability)
                .map(PokemonApiResponse.AbilityDetail::name)
                .map(this::toAbilityEnumName)
                .filter(this::isSupportedAbility)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        return validAbilities.isEmpty() ? List.of(Ability.NONE.name()) : validAbilities;
    }

    @Cacheable("pokemonSpeciesOptions")
    public List<String> getAvailableSpecies() {
        PokemonListApiResponse apiResponse = restClient.get()
                .uri("/pokemon?limit=2000")
                .retrieve()
                .body(PokemonListApiResponse.class);

        if (apiResponse == null || apiResponse.results() == null) {
            return List.of();
        }

        java.util.List<String> species = new java.util.ArrayList<>(apiResponse.results().stream()
                .map(PokemonListApiResponse.PokemonListEntry::name)
                .filter(this::isSelectableSpecies)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList());
        if (!species.contains(ASH_PIKACHU)) {
            species.add(ASH_PIKACHU);
            species.sort(Comparator.naturalOrder());
        }
        return species;
    }

    @Cacheable("pokemonMoveOptions")
    public List<String> getValidMoves(String speciesName) {
        if (isAshPikachu(speciesName)) {
            return getValidMoves(BASE_PIKACHU);
        }

        PokemonApiResponse apiResponse = fetchPokemon(speciesName);

        if (apiResponse == null) {
            throw new IllegalArgumentException("Pokemon not found: " + speciesName);
        }

        return apiResponse.moves().stream()
                .map(PokemonApiResponse.MoveSlot::move)
                .map(PokemonApiResponse.MoveDetail::name)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Cacheable("pokemonBaseStats")
    public SpeciesStatsResponse getSpeciesBaseStats(String speciesName) {
        if (isAshPikachu(speciesName)) {
            return new SpeciesStatsResponse(45, 80, 50, 75, 60, 120);
        }

        PokemonApiResponse apiResponse = fetchPokemon(speciesName);

        if (apiResponse == null) {
            throw new IllegalArgumentException("Pokemon not found: " + speciesName);
        }

        return new SpeciesStatsResponse(
                getBaseStat(apiResponse, "hp"),
                getBaseStat(apiResponse, "attack"),
                getBaseStat(apiResponse, "defense"),
                getBaseStat(apiResponse, "special-attack"),
                getBaseStat(apiResponse, "special-defense"),
                getBaseStat(apiResponse, "speed")
        );
    }

    public void applyMegaEvolution(BattlePokemon pokemon, String megaSpeciesName, com.example.battlesimulator.model.enums.Ability megaAbility) {
        PokemonApiResponse apiResponse = fetchPokemon(megaSpeciesName);

        if (apiResponse == null) throw new IllegalArgumentException("Pokemon not found: " + megaSpeciesName);

        Type type1 = Type.valueOf(apiResponse.types().get(0).type().name().toUpperCase());
        Type type2 = apiResponse.types().size() > 1
                ? Type.valueOf(apiResponse.types().get(1).type().name().toUpperCase()) : Type.NONE;

        int baseHp  = getBaseStat(apiResponse, "hp");
        int baseAtk = getBaseStat(apiResponse, "attack");
        int baseDef = getBaseStat(apiResponse, "defense");
        int baseSpA = getBaseStat(apiResponse, "special-attack");
        int baseSpD = getBaseStat(apiResponse, "special-defense");
        int baseSpe = getBaseStat(apiResponse, "speed");

        int currentHp = pokemon.getCurrentHp();
        int finalHp = calculateHpStat(baseHp, pokemon.getIvs().hp(), pokemon.getEvs().hp(), pokemon.getLevel());

        pokemon.setSpeciesId(apiResponse.name());
        pokemon.setType1(type1);
        pokemon.setType2(type2);
        pokemon.setBaseType1(type1);
        pokemon.setBaseType2(type2);
        pokemon.setMaxHp(finalHp);
        pokemon.setCurrentHp(Math.min(currentHp, finalHp));
        pokemon.setAttack(calculateOtherStat(baseAtk, pokemon.getIvs().attack(), pokemon.getEvs().attack(), pokemon.getLevel(), pokemon.getNature(), Stat.ATTACK));
        pokemon.setDefense(calculateOtherStat(baseDef, pokemon.getIvs().defense(), pokemon.getEvs().defense(), pokemon.getLevel(), pokemon.getNature(), Stat.DEFENSE));
        pokemon.setSpecialAttack(calculateOtherStat(baseSpA, pokemon.getIvs().specialAttack(), pokemon.getEvs().specialAttack(), pokemon.getLevel(), pokemon.getNature(), Stat.SPECIAL_ATTACK));
        pokemon.setSpecialDefense(calculateOtherStat(baseSpD, pokemon.getIvs().specialDefense(), pokemon.getEvs().specialDefense(), pokemon.getLevel(), pokemon.getNature(), Stat.SPECIAL_DEFENSE));
        pokemon.setSpeed(calculateOtherStat(baseSpe, pokemon.getIvs().speed(), pokemon.getEvs().speed(), pokemon.getLevel(), pokemon.getNature(), Stat.SPEED));
        pokemon.setAbility(megaAbility);
        pokemon.setMegaEvolved(true);
        if (!pokemon.getNickname().contains("(Mega)")) {
            pokemon.setNickname(pokemon.getNickname() + " (Mega)");
        }
    }

    private int getBaseStat(PokemonApiResponse response, String statName) {
        return response.stats().stream()
                .filter(s -> s.stat().name().equals(statName))
                .map(PokemonApiResponse.StatSlot::base_stat)
                .findFirst().orElse(50);
    }

    private int calculateHpStat(int base, int iv, int ev, int level) {
        if (base == 1) return 1; // Shedinja
        int statPreLevel = (2 * base) + iv + (ev / 4);
        return (statPreLevel * level / 100) + level + 10;
    }

    private int calculateOtherStat(int base, int iv, int ev, int level, Nature nature, Stat statType) {
        int statPreLevel = (2 * base) + iv + (ev / 4);
        int unnaturedStat = (statPreLevel * level / 100) + 5;
        return (int) Math.floor(unnaturedStat * nature.getMultiplier(statType));
    }

    @Cacheable("moveData")
    public Move getMove(String moveName) {
        String formattedName = normalizeMoveName(moveName);

        MoveApiResponse apiResponse = restClient.get()
                .uri("/move/{name}", formattedName)
                .retrieve()
                .body(MoveApiResponse.class);

        if (apiResponse == null) throw new IllegalArgumentException("Move not found: " + moveName);

        Type type = Type.valueOf(apiResponse.type().name().toUpperCase());
        MoveCategory category;
        try { category = MoveCategory.valueOf(apiResponse.damage_class().name().toUpperCase()); }
        catch (IllegalArgumentException e) { category = MoveCategory.OTHER; }
        int power = (apiResponse.power() != null) ? apiResponse.power() : 0;
        int accuracy = (apiResponse.accuracy() != null) ? apiResponse.accuracy() : 0;

        return new Move(apiResponse.name(), moveName, type, category, power, accuracy,
                apiResponse.pp(), apiResponse.priority());
    }

    private String normalizeMoveName(String moveName) {
        String formattedName = moveName.toLowerCase().trim().replace(" ", "-");
        String compactName = formattedName.replace("-", "");
        return MOVE_ALIASES.getOrDefault(compactName, formattedName);
    }

    private String toAbilityEnumName(String abilityName) {
        return abilityName.toUpperCase().replace('-', '_');
    }

    private boolean isSupportedAbility(String abilityName) {
        try {
            Ability.valueOf(abilityName);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean isSelectableSpecies(String speciesName) {
        if (speciesName == null || speciesName.isBlank()) {
            return false;
        }
        if (isAshPikachu(speciesName)) {
            return true;
        }
        if (speciesName.contains("-mega")) {
            return SUPPORTED_MEGA_SPECIES.contains(speciesName);
        }
        return true;
    }

    private boolean isAshPikachu(String speciesName) {
        return ASH_PIKACHU.equalsIgnoreCase(speciesName);
    }

    private BattlePokemon createAshPikachu(int level, StatBlock ivs, StatBlock evs, Nature nature) {
        Type type1 = Type.ELECTRIC;
        Type type2 = Type.NONE;

        int baseHp = 45;
        int baseAtk = 80;
        int baseDef = 50;
        int baseSpA = 75;
        int baseSpD = 60;
        int baseSpe = 120;

        int finalHp = calculateHpStat(baseHp, ivs.hp(), evs.hp(), level);

        return BattlePokemon.builder()
                .speciesId(ASH_PIKACHU)
                .originalSpeciesId(ASH_PIKACHU)
                .nickname(ASH_PIKACHU)
                .level(level)
                .nature(nature)
                .ivs(ivs)
                .evs(evs)
                .type1(type1)
                .type2(type2)
                .baseType1(type1)
                .baseType2(type2)
                .maxHp(finalHp)
                .currentHp(finalHp)
                .attack(calculateOtherStat(baseAtk, ivs.attack(), evs.attack(), level, nature, Stat.ATTACK))
                .defense(calculateOtherStat(baseDef, ivs.defense(), evs.defense(), level, nature, Stat.DEFENSE))
                .specialAttack(calculateOtherStat(baseSpA, ivs.specialAttack(), evs.specialAttack(), level, nature, Stat.SPECIAL_ATTACK))
                .specialDefense(calculateOtherStat(baseSpD, ivs.specialDefense(), evs.specialDefense(), level, nature, Stat.SPECIAL_DEFENSE))
                .speed(calculateOtherStat(baseSpe, ivs.speed(), evs.speed(), level, nature, Stat.SPEED))
                .moves(new ArrayList<>())
                .build();
    }

    private PokemonApiResponse fetchPokemon(String speciesName) {
        return restClient.get()
                .uri("/pokemon/{name}", speciesName.toLowerCase())
                .retrieve()
                .body(PokemonApiResponse.class);
    }
}
