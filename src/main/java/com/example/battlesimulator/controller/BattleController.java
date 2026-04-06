package com.example.battlesimulator.controller;

import com.example.battlesimulator.dto.BattleInitRequest;
import com.example.battlesimulator.dto.PlayerAction;
import com.example.battlesimulator.dto.TurnResult;
import com.example.battlesimulator.service.BattleEngineService;
import com.example.battlesimulator.service.PokeApiService;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/battle")
@CrossOrigin(origins = "*")
public class BattleController {

    private final BattleEngineService battleEngineService;
    private final PokeApiService pokeApiService;

    public BattleController(BattleEngineService battleEngineService, PokeApiService pokeApiService) {
        this.battleEngineService = battleEngineService;
        this.pokeApiService = pokeApiService;
    }

    @PostMapping("/setup")
    public ResponseEntity<TurnResult> setupPokemon(@RequestBody BattleInitRequest request) {
        try {
            TurnResult result = battleEngineService.initializePokemon(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("Setup error for slot " + request.pokemonSlot() + ": " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{battleId}")
    public ResponseEntity<TurnResult> getBattle(@PathVariable String battleId) {
        TurnResult result = battleEngineService.getBattleSnapshot(battleId);
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }

    @GetMapping("/species/{speciesName}/abilities")
    public ResponseEntity<List<String>> getSpeciesAbilities(@PathVariable String speciesName) {
        try {
            return ResponseEntity.ok(pokeApiService.getValidAbilities(speciesName));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/species")
    public ResponseEntity<List<String>> getSpeciesList() {
        try {
            return ResponseEntity.ok(pokeApiService.getAvailableSpecies());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/species/{speciesName}/moves")
    public ResponseEntity<List<String>> getSpeciesMoves(@PathVariable String speciesName) {
        try {
            return ResponseEntity.ok(pokeApiService.getValidMoves(speciesName));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @MessageMapping("/action")
    public void handleAction(PlayerAction action) {
        battleEngineService.handleAction(action);
    }
}
