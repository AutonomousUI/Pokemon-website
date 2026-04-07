package com.example.battlesimulator.service;

import com.example.battlesimulator.dto.PlayerAction;
import com.example.battlesimulator.model.BattlePokemon;
import com.example.battlesimulator.model.BattleSession;
import com.example.battlesimulator.model.Move;
import com.example.battlesimulator.model.enums.HeldItem;
import com.example.battlesimulator.model.enums.MoveCategory;
import com.example.battlesimulator.model.enums.Type;
import com.example.battlesimulator.util.TypeChart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GroqBattleAiService {

    private static final String GROQ_URL = "https://api.groq.com/openai/v1";
    private static final int COMPETITIVE_SEARCH_DEPTH = 4;
    private static final int COMPETITIVE_MAX_MOVES = 3;
    private static final int COMPETITIVE_MAX_SWITCHES = 2;
    private static final double SEARCH_WIN_SCORE = 10000.0;

    private final ObjectMapper objectMapper;
    private final RestClient groqClient;
    private final String groqApiKey;
    private final String groqModel;

    public GroqBattleAiService(@Value("${groq.api.key:}") String groqApiKey,
                               @Value("${groq.model:llama-3.3-70b-versatile}") String groqModel) {
        this.objectMapper = new ObjectMapper();
        this.groqApiKey = groqApiKey;
        this.groqModel = groqModel;
        this.groqClient = RestClient.builder().baseUrl(GROQ_URL).build();
    }

    public PlayerAction chooseAction(String battleId, String aiPlayerId, BattleSession session) {
        boolean competitiveMode = "competitive".equalsIgnoreCase(session.getAiMode());
        if (aiMustSwitch(session, aiPlayerId)) {
            return competitiveMode
                    ? chooseCompetitiveSwitch(battleId, aiPlayerId, session)
                    : chooseFallbackSwitch(battleId, aiPlayerId, session);
        }

        PlayerAction fallback = competitiveMode
                ? chooseCompetitiveMove(battleId, aiPlayerId, session)
                : chooseFallbackMove(battleId, aiPlayerId, session);
        if (competitiveMode) {
            return fallback;
        }
        if (groqApiKey == null || groqApiKey.isBlank()) {
            return fallback;
        }

        try {
            String prompt = buildPrompt(session, aiPlayerId);
            Map<String, Object> payload = Map.of(
                    "model", groqModel,
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "You are a competitive Pokemon battle AI. " +
                                    "Reply with a single JSON object only. " +
                                    "Use actionType MOVE or SWITCH. " +
                                    "If MOVE, include targetId. If SWITCH, include switchIndex. " +
                                    "Only choose legal actions from the provided state."),
                            Map.of("role", "user", "content", prompt)
                    )
            );

            JsonNode response = groqClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + groqApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response.path("choices").path(0).path("message").path("content").asText("");
            PlayerAction parsed = parseAiAction(content, battleId, aiPlayerId);
            return isValidAction(parsed, session) ? parsed : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    public PlayerAction chooseForcedSwitchAction(String battleId, String aiPlayerId, BattleSession session) {
        return "competitive".equalsIgnoreCase(session.getAiMode())
                ? chooseCompetitiveSwitch(battleId, aiPlayerId, session)
                : chooseFallbackSwitch(battleId, aiPlayerId, session);
    }

    private String buildPrompt(BattleSession session, String aiPlayerId) throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("aiPlayerId", aiPlayerId);
        state.put("phase", session.getPhase().name());
        state.put("turn", session.getTurnCount());
        state.put("weather", session.getWeather().name());
        state.put("terrain", session.getTerrain().name());
        state.put("history", session.getBattleHistory());
        state.put("player1", buildTeamState(session.getPlayer1Team(), session.getPlayer1ActiveIndex()));
        state.put("player2", buildTeamState(session.getPlayer2Team(), session.getPlayer2ActiveIndex()));

        return """
                Current battle state:
                %s

                Decide the best legal action for %s right now.
                Output JSON in this exact shape:
                {
                  "actionType": "MOVE" or "SWITCH",
                  "targetId": "move-id-or-null",
                  "switchIndex": 0-5 or null,
                  "reasoning": "short explanation"
                }
                """.formatted(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state), aiPlayerId);
    }

    private List<Map<String, Object>> buildTeamState(List<BattlePokemon> team, int activeIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < team.size(); i++) {
            BattlePokemon pokemon = team.get(i);
            if (pokemon == null) {
                result.add(null);
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("teamIndex", i);
            entry.put("active", i == activeIndex);
            entry.put("speciesId", pokemon.getSpeciesId());
            entry.put("nickname", pokemon.getNickname());
            entry.put("hp", Map.of("current", pokemon.getCurrentHp(), "max", pokemon.getMaxHp()));
            entry.put("fainted", pokemon.isFainted());
            entry.put("status", pokemon.getStatusCondition().name());
            entry.put("ability", pokemon.getAbility().name());
            entry.put("heldItem", pokemon.getHeldItem().name());
            entry.put("types", List.of(pokemon.getType1().name(), pokemon.getType2().name()));
            entry.put("stats", Map.of(
                    "attack", pokemon.getAttack(),
                    "defense", pokemon.getDefense(),
                    "specialAttack", pokemon.getSpecialAttack(),
                    "specialDefense", pokemon.getSpecialDefense(),
                    "speed", pokemon.getSpeed()
            ));
            entry.put("stages", Map.of(
                    "attack", pokemon.getAttackStage(),
                    "defense", pokemon.getDefenseStage(),
                    "specialAttack", pokemon.getSpecialAttackStage(),
                    "specialDefense", pokemon.getSpecialDefenseStage(),
                    "speed", pokemon.getSpeedStage(),
                    "accuracy", pokemon.getAccuracyStage(),
                    "evasion", pokemon.getEvasionStage()
            ));
            Map<String, Object> volatileState = new LinkedHashMap<>();
            volatileState.put("confused", pokemon.isConfused());
            volatileState.put("taunted", pokemon.isTaunted());
            volatileState.put("trapped", pokemon.isTrapped());
            volatileState.put("choiceLock", pokemon.getChoiceLock());
            entry.put("volatile", volatileState);
            entry.put("moves", pokemon.getMoves().stream().map(move -> Map.of(
                    "id", move.id(),
                    "name", move.name(),
                    "type", move.type().name(),
                    "category", move.category().name(),
                    "power", move.basePower(),
                    "priority", move.priority()
            )).toList());
            result.add(entry);
        }
        return result;
    }

    private PlayerAction parseAiAction(String rawContent, String battleId, String aiPlayerId) throws Exception {
        String cleaned = rawContent == null ? "" : rawContent.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "").replaceFirst("\\s*```$", "");
        }
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
        }

        JsonNode node = objectMapper.readTree(cleaned);
        String actionType = node.path("actionType").asText("");
        String targetId = node.path("targetId").isNull() ? null : node.path("targetId").asText(null);
        Integer switchIndex = node.path("switchIndex").isNull() || node.path("switchIndex").isMissingNode()
                ? null : node.path("switchIndex").asInt();
        return new PlayerAction(battleId, aiPlayerId, actionType, targetId, switchIndex);
    }

    private boolean isValidAction(PlayerAction action, BattleSession session) {
        if (action == null) {
            return false;
        }
        String playerId = action.playerId();
        BattlePokemon active = "player1".equals(playerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        List<BattlePokemon> team = "player1".equals(playerId) ? session.getPlayer1Team() : session.getPlayer2Team();

        if ("MOVE".equalsIgnoreCase(action.actionType())) {
            if (active == null || active.isFainted() || action.targetId() == null) {
                return false;
            }
            return active.getMoves().stream().anyMatch(move -> move.id().equalsIgnoreCase(action.targetId()));
        }
        if ("SWITCH".equalsIgnoreCase(action.actionType())) {
            if (action.switchIndex() == null || action.switchIndex() < 0 || action.switchIndex() >= team.size()) {
                return false;
            }
            BattlePokemon target = team.get(action.switchIndex());
            return target != null && !target.isFainted() && target != active;
        }
        return false;
    }

    private boolean aiMustSwitch(BattleSession session, String aiPlayerId) {
        return switch (session.getPhase()) {
            case WAITING_FOR_SWITCH_P1 -> "player1".equals(aiPlayerId);
            case WAITING_FOR_SWITCH_P2 -> "player2".equals(aiPlayerId);
            case WAITING_FOR_SWITCH_BOTH -> true;
            default -> false;
        };
    }

    private PlayerAction chooseFallbackSwitch(String battleId, String aiPlayerId, BattleSession session) {
        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        BattlePokemon opponent = "player1".equals(aiPlayerId) ? session.getPlayer2Active() : session.getPlayer1Active();

        Integer bestIndex = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < team.size(); i++) {
            BattlePokemon candidate = team.get(i);
            if (candidate == null || candidate.isFainted() || candidate == active) {
                continue;
            }

            double score = heuristicSwitchScore(candidate, opponent);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestIndex != null) {
            return new PlayerAction(battleId, aiPlayerId, "SWITCH", null, bestIndex);
        }
        return new PlayerAction(battleId, aiPlayerId, "MOVE", active != null && !active.getMoves().isEmpty() ? active.getMoves().get(0).id() : null, null);
    }

    private PlayerAction chooseCompetitiveSwitch(String battleId, String aiPlayerId, BattleSession session) {
        SearchAction bestAction = findBestCompetitiveAction(aiPlayerId, session, true);
        if (bestAction != null && bestAction.isSwitch()) {
            return new PlayerAction(battleId, aiPlayerId, "SWITCH", null, bestAction.switchIndex());
        }
        return chooseFallbackSwitch(battleId, aiPlayerId, session);
    }

    private PlayerAction chooseFallbackMove(String battleId, String aiPlayerId, BattleSession session) {
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        BattlePokemon defender = "player1".equals(aiPlayerId) ? session.getPlayer2Active() : session.getPlayer1Active();
        if (active == null || active.isFainted()) {
            return chooseFallbackSwitch(battleId, aiPlayerId, session);
        }

        Move forcedMove = getChoiceLockedMove(active);
        if (forcedMove != null) {
            double forcedScore = heuristicMoveScore(forcedMove, active, defender);
            if (shouldSwitchInsteadOfLockedMove(active, defender, forcedScore, battleId, aiPlayerId, session)) {
                return chooseFallbackSwitch(battleId, aiPlayerId, session);
            }
            return new PlayerAction(battleId, aiPlayerId, "MOVE", forcedMove.id(), null);
        }

        Move bestMove = active.getMoves().stream()
                .filter(move -> move != null)
                .max(Comparator.comparingDouble(move -> heuristicMoveScore(move, active, defender)))
                .orElse(null);

        if (bestMove == null) {
            return chooseFallbackSwitch(battleId, aiPlayerId, session);
        }
        double bestMoveScore = heuristicMoveScore(bestMove, active, defender);
        if (shouldSwitchAgainstSetup(active, defender, bestMove, bestMoveScore, battleId, aiPlayerId, session)) {
            return chooseFallbackSwitch(battleId, aiPlayerId, session);
        }
        return new PlayerAction(battleId, aiPlayerId, "MOVE", bestMove.id(), null);
    }

    private PlayerAction chooseCompetitiveMove(String battleId, String aiPlayerId, BattleSession session) {
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        BattlePokemon defender = "player1".equals(aiPlayerId) ? session.getPlayer2Active() : session.getPlayer1Active();
        if (active == null || active.isFainted()) {
            return chooseCompetitiveSwitch(battleId, aiPlayerId, session);
        }

        Move forcedMove = getChoiceLockedMove(active);
        if (forcedMove != null) {
            double forcedScore = competitiveMoveScore(forcedMove, active, defender);
            PlayerAction bestSwitch = chooseCompetitiveSwitch(battleId, aiPlayerId, session);
            if (shouldTakeCompetitiveSwitch(active, defender, forcedScore, bestSwitch, aiPlayerId, session)) {
                return bestSwitch;
            }
            return new PlayerAction(battleId, aiPlayerId, "MOVE", forcedMove.id(), null);
        }

        SearchAction bestAction = findBestCompetitiveAction(aiPlayerId, session, false);
        if (bestAction == null) {
            return chooseFallbackMove(battleId, aiPlayerId, session);
        }
        if (bestAction.isSwitch()) {
            return new PlayerAction(battleId, aiPlayerId, "SWITCH", null, bestAction.switchIndex());
        }
        return new PlayerAction(battleId, aiPlayerId, "MOVE", bestAction.moveId(), null);
    }

    private SearchAction findBestCompetitiveAction(String aiPlayerId, BattleSession session, boolean forceSwitch) {
        SearchState root = buildSearchState(aiPlayerId, session);
        if (root == null) {
            return null;
        }

        List<SearchAction> actions = generateSearchActions(root, forceSwitch);
        if (actions.isEmpty()) {
            return null;
        }

        SearchAction bestAction = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;
        for (SearchAction action : actions) {
            SearchState next = applySearchAction(root, action);
            double value = minimax(next, COMPETITIVE_SEARCH_DEPTH - 1, alpha, beta);
            if (value > bestValue) {
                bestValue = value;
                bestAction = action;
            }
            alpha = Math.max(alpha, bestValue);
        }
        return bestAction;
    }

    private double competitiveMoveActionScore(Move move, BattlePokemon attacker, BattlePokemon defender) {
        double immediate = competitiveMoveScore(move, attacker, defender);
        if (move == null || attacker == null) {
            return immediate;
        }
        if (defender == null || defender.isFainted()) {
            return immediate;
        }

        BattlePokemon projectedAttacker = copyForEvaluation(attacker);
        BattlePokemon projectedDefender = copyForEvaluation(defender);
        applyProjectedMoveOutcome(move, projectedAttacker, projectedDefender);

        if (projectedDefender.isFainted()) {
            return immediate + 120.0;
        }

        double boardAdvantage = projectedBoardAdvantage(projectedAttacker, projectedDefender);
        double opponentReply = estimateBestIncomingThreat(projectedDefender, projectedAttacker);
        return immediate + (boardAdvantage * 0.35) - (opponentReply * 0.70);
    }

    private double competitiveSwitchActionScore(BattlePokemon candidate, BattlePokemon opponent) {
        double immediate = competitiveSwitchScore(candidate, opponent);
        if (candidate == null || opponent == null || opponent.isFainted()) {
            return immediate;
        }

        BattlePokemon projectedCandidate = copyForEvaluation(candidate);
        BattlePokemon projectedOpponent = copyForEvaluation(opponent);
        double opponentReply = estimateBestIncomingThreat(projectedOpponent, projectedCandidate);
        double boardAdvantage = projectedBoardAdvantage(projectedCandidate, projectedOpponent);
        return immediate + (boardAdvantage * 0.25) - (opponentReply * 0.45);
    }

    private double minimax(SearchState state, int depth, double alpha, double beta) {
        if (depth <= 0 || isSearchTerminal(state)) {
            return evaluateSearchState(state);
        }

        List<SearchAction> actions = generateSearchActions(state, false);
        if (actions.isEmpty()) {
            return evaluateSearchState(state);
        }

        if (state.aiTurn()) {
            double best = Double.NEGATIVE_INFINITY;
            for (SearchAction action : actions) {
                best = Math.max(best, minimax(applySearchAction(state, action), depth - 1, alpha, beta));
                alpha = Math.max(alpha, best);
                if (beta <= alpha) {
                    break;
                }
            }
            return best;
        }

        double best = Double.POSITIVE_INFINITY;
        for (SearchAction action : actions) {
            best = Math.min(best, minimax(applySearchAction(state, action), depth - 1, alpha, beta));
            beta = Math.min(beta, best);
            if (beta <= alpha) {
                break;
            }
        }
        return best;
    }

    private boolean isSearchTerminal(SearchState state) {
        return !hasLivingSide(state.aiActive(), state.aiBench())
                || !hasLivingSide(state.opponentActive(), state.opponentBench());
    }

    private boolean hasLivingSide(BattlePokemon active, List<BenchPokemon> bench) {
        if (active != null && !active.isFainted()) {
            return true;
        }
        return bench != null && bench.stream().anyMatch(entry -> entry.pokemon() != null && !entry.pokemon().isFainted());
    }

    private double evaluateSearchState(SearchState state) {
        boolean aiAlive = hasLivingSide(state.aiActive(), state.aiBench());
        boolean oppAlive = hasLivingSide(state.opponentActive(), state.opponentBench());
        if (!aiAlive && !oppAlive) {
            return 0.0;
        }
        if (!aiAlive) {
            return -SEARCH_WIN_SCORE;
        }
        if (!oppAlive) {
            return SEARCH_WIN_SCORE;
        }

        double aiTeam = teamMaterialScore(state.aiActive(), state.aiBench());
        double oppTeam = teamMaterialScore(state.opponentActive(), state.opponentBench());
        double activeBoard = projectedBoardAdvantage(state.aiActive(), state.opponentActive()) * 0.75;
        return (aiTeam - oppTeam) + activeBoard;
    }

    private double teamMaterialScore(BattlePokemon active, List<BenchPokemon> bench) {
        double score = pokemonMaterialScore(active, true);
        if (bench != null) {
            for (BenchPokemon entry : bench) {
                score += pokemonMaterialScore(entry.pokemon(), false);
            }
        }
        return score;
    }

    private double pokemonMaterialScore(BattlePokemon pokemon, boolean active) {
        if (pokemon == null || pokemon.isFainted()) {
            return 0.0;
        }
        double hpPercent = pokemon.getMaxHp() > 0 ? (pokemon.getCurrentHp() * 100.0 / pokemon.getMaxHp()) : 0.0;
        double statValue = (pokemon.getAttack() + pokemon.getDefense() + pokemon.getSpecialAttack()
                + pokemon.getSpecialDefense() + pokemon.getSpeed()) * 0.04;
        double boostValue = (pokemon.getAttackStage() + pokemon.getDefenseStage() + pokemon.getSpecialAttackStage()
                + pokemon.getSpecialDefenseStage() + pokemon.getSpeedStage()) * 6.0;
        return hpPercent + statValue + boostValue + (active ? 20.0 : 0.0);
    }

    private List<SearchAction> generateSearchActions(SearchState state, boolean forceSwitch) {
        BattlePokemon active = state.aiTurn() ? state.aiActive() : state.opponentActive();
        BattlePokemon defender = state.aiTurn() ? state.opponentActive() : state.aiActive();
        List<BenchPokemon> bench = state.aiTurn() ? state.aiBench() : state.opponentBench();

        if (active == null || active.isFainted()) {
            return generateForcedSwitchActions(bench);
        }
        if (forceSwitch) {
            return generateForcedSwitchActions(bench);
        }

        List<SearchAction> actions = new ArrayList<>();
        Move lockedMove = getChoiceLockedMove(active);
        if (lockedMove != null) {
            actions.add(SearchAction.move(lockedMove.id(), competitiveMoveActionScore(lockedMove, active, defender)));
        } else {
            List<SearchAction> moves = active.getMoves().stream()
                    .filter(move -> move != null)
                    .map(move -> SearchAction.move(move.id(), competitiveMoveActionScore(move, active, defender)))
                    .sorted(Comparator.comparingDouble(SearchAction::orderingScore).reversed())
                    .limit(COMPETITIVE_MAX_MOVES)
                    .toList();
            actions.addAll(moves);
        }

        if (!active.isTrapped()) {
            List<SearchAction> switches = bench.stream()
                    .filter(entry -> entry.pokemon() != null && !entry.pokemon().isFainted())
                    .map(entry -> SearchAction.switchTo(entry.teamIndex(), competitiveSwitchActionScore(entry.pokemon(), defender)))
                    .sorted(Comparator.comparingDouble(SearchAction::orderingScore).reversed())
                    .limit(COMPETITIVE_MAX_SWITCHES)
                    .toList();
            actions.addAll(switches);
        }

        actions.sort(Comparator.comparingDouble(SearchAction::orderingScore).reversed());
        return actions;
    }

    private List<SearchAction> generateForcedSwitchActions(List<BenchPokemon> bench) {
        if (bench == null) {
            return List.of();
        }
        return bench.stream()
                .filter(entry -> entry.pokemon() != null && !entry.pokemon().isFainted())
                .map(entry -> SearchAction.switchTo(entry.teamIndex(), 0.0))
                .toList();
    }

    private SearchState applySearchAction(SearchState state, SearchAction action) {
        SearchState next = copySearchState(state);
        boolean aiSide = next.aiTurn();
        BattlePokemon attacker = aiSide ? next.aiActive() : next.opponentActive();
        BattlePokemon defender = aiSide ? next.opponentActive() : next.aiActive();
        List<BenchPokemon> actorBench = aiSide ? next.aiBench() : next.opponentBench();
        List<BenchPokemon> defenderBench = aiSide ? next.opponentBench() : next.aiBench();

        if (action.isSwitch()) {
            performProjectedSwitch(next, aiSide, action.switchIndex());
            next.setAiTurn(!next.aiTurn());
            return next;
        }

        Move move = findMoveById(attacker, action.moveId());
        applyProjectedMoveOutcome(move, attacker, defender);
        if (defender != null && defender.isFainted()) {
            if (!bringInFirstHealthy(defenderBench, next, !aiSide)) {
                if (aiSide) {
                    next.setOpponentActive(defender);
                } else {
                    next.setAiActive(defender);
                }
            }
        }
        next.setAiTurn(!next.aiTurn());
        return next;
    }

    private void performProjectedSwitch(SearchState state, boolean aiSide, int switchIndex) {
        List<BenchPokemon> bench = aiSide ? state.aiBench() : state.opponentBench();
        BattlePokemon current = aiSide ? state.aiActive() : state.opponentActive();
        int targetPos = findBenchPosition(bench, switchIndex);
        if (targetPos < 0) {
            return;
        }
        BenchPokemon chosen = bench.remove(targetPos);
        if (current != null && !current.isFainted()) {
            bench.add(new BenchPokemon(switchIndexOfCurrent(current, bench, switchIndex), current));
        }
        if (aiSide) {
            state.setAiActive(chosen.pokemon());
        } else {
            state.setOpponentActive(chosen.pokemon());
        }
    }

    private int switchIndexOfCurrent(BattlePokemon current, List<BenchPokemon> bench, int fallback) {
        return bench.stream()
                .map(BenchPokemon::teamIndex)
                .max(Integer::compareTo)
                .orElse(fallback + 10) + 1;
    }

    private boolean bringInFirstHealthy(List<BenchPokemon> bench, SearchState state, boolean aiSide) {
        if (bench == null) {
            return false;
        }
        for (int i = 0; i < bench.size(); i++) {
            BenchPokemon option = bench.get(i);
            if (option.pokemon() != null && !option.pokemon().isFainted()) {
                bench.remove(i);
                if (aiSide) {
                    state.setAiActive(option.pokemon());
                } else {
                    state.setOpponentActive(option.pokemon());
                }
                return true;
            }
        }
        return false;
    }

    private int findBenchPosition(List<BenchPokemon> bench, int teamIndex) {
        if (bench == null) {
            return -1;
        }
        for (int i = 0; i < bench.size(); i++) {
            if (bench.get(i).teamIndex() == teamIndex) {
                return i;
            }
        }
        return -1;
    }

    private Move findMoveById(BattlePokemon attacker, String moveId) {
        if (attacker == null || attacker.getMoves() == null || moveId == null) {
            return null;
        }
        return attacker.getMoves().stream()
                .filter(move -> move != null && move.id().equalsIgnoreCase(moveId))
                .findFirst()
                .orElse(null);
    }

    private SearchState buildSearchState(String aiPlayerId, BattleSession session) {
        boolean aiIsPlayerOne = "player1".equals(aiPlayerId);
        List<BattlePokemon> aiTeam = aiIsPlayerOne ? session.getPlayer1Team() : session.getPlayer2Team();
        List<BattlePokemon> oppTeam = aiIsPlayerOne ? session.getPlayer2Team() : session.getPlayer1Team();
        int aiActiveIndex = aiIsPlayerOne ? session.getPlayer1ActiveIndex() : session.getPlayer2ActiveIndex();
        int oppActiveIndex = aiIsPlayerOne ? session.getPlayer2ActiveIndex() : session.getPlayer1ActiveIndex();

        return new SearchState(
                copyForEvaluation(getTeamPokemon(aiTeam, aiActiveIndex)),
                copyBench(aiTeam, aiActiveIndex),
                copyForEvaluation(getTeamPokemon(oppTeam, oppActiveIndex)),
                copyBench(oppTeam, oppActiveIndex),
                true
        );
    }

    private BattlePokemon getTeamPokemon(List<BattlePokemon> team, int index) {
        if (team == null || index < 0 || index >= team.size()) {
            return null;
        }
        return team.get(index);
    }

    private List<BenchPokemon> copyBench(List<BattlePokemon> team, int activeIndex) {
        List<BenchPokemon> bench = new ArrayList<>();
        if (team == null) {
            return bench;
        }
        for (int i = 0; i < team.size(); i++) {
            if (i == activeIndex) {
                continue;
            }
            BattlePokemon pokemon = team.get(i);
            if (pokemon != null) {
                bench.add(new BenchPokemon(i, copyForEvaluation(pokemon)));
            }
        }
        return bench;
    }

    private SearchState copySearchState(SearchState state) {
        return new SearchState(
                copyForEvaluation(state.aiActive()),
                copyBenchEntries(state.aiBench()),
                copyForEvaluation(state.opponentActive()),
                copyBenchEntries(state.opponentBench()),
                state.aiTurn()
        );
    }

    private List<BenchPokemon> copyBenchEntries(List<BenchPokemon> bench) {
        List<BenchPokemon> copied = new ArrayList<>();
        if (bench == null) {
            return copied;
        }
        for (BenchPokemon entry : bench) {
            copied.add(new BenchPokemon(entry.teamIndex(), copyForEvaluation(entry.pokemon())));
        }
        return copied;
    }

    private double projectedBoardAdvantage(BattlePokemon attacker, BattlePokemon defender) {
        if (attacker == null) {
            return -100.0;
        }
        if (defender == null || defender.isFainted()) {
            return 180.0;
        }

        double attackerHpPercent = attacker.getMaxHp() > 0 ? (attacker.getCurrentHp() * 100.0 / attacker.getMaxHp()) : 0.0;
        double defenderHpPercent = defender.getMaxHp() > 0 ? (defender.getCurrentHp() * 100.0 / defender.getMaxHp()) : 0.0;
        double hpSwing = attackerHpPercent - defenderHpPercent;
        double speedEdge = (attacker.getSpeed() - defender.getSpeed()) * 0.08;
        double boostEdge = (attacker.getAttackStage() + attacker.getSpecialAttackStage() + attacker.getSpeedStage()
                - defender.getAttackStage() - defender.getSpecialAttackStage() - defender.getSpeedStage()) * 14.0;
        double offensiveEdge = bestProjectedOutgoing(attacker, defender) - bestProjectedOutgoing(defender, attacker);
        return hpSwing + speedEdge + boostEdge + offensiveEdge;
    }

    private double bestProjectedOutgoing(BattlePokemon attacker, BattlePokemon defender) {
        if (attacker == null || attacker.getMoves() == null) {
            return -50.0;
        }
        return attacker.getMoves().stream()
                .filter(move -> move != null)
                .mapToDouble(move -> competitiveMoveScore(move, attacker, defender))
                .max()
                .orElse(-50.0);
    }

    private void applyProjectedMoveOutcome(Move move, BattlePokemon attacker, BattlePokemon defender) {
        if (move == null || attacker == null) {
            return;
        }

        String moveId = move.id() == null ? "" : move.id().toLowerCase();
        if (move.category() == MoveCategory.STATUS) {
            switch (moveId) {
                case "dragon-dance" -> {
                    attacker.setAttackStage(attacker.getAttackStage() + 1);
                    attacker.setSpeedStage(attacker.getSpeedStage() + 1);
                }
                case "swords-dance" -> attacker.setAttackStage(attacker.getAttackStage() + 2);
                case "nasty-plot", "tail-glow" -> attacker.setSpecialAttackStage(attacker.getSpecialAttackStage() + 2);
                case "calm-mind" -> {
                    attacker.setSpecialAttackStage(attacker.getSpecialAttackStage() + 1);
                    attacker.setSpecialDefenseStage(attacker.getSpecialDefenseStage() + 1);
                }
                case "quiver-dance" -> {
                    attacker.setSpecialAttackStage(attacker.getSpecialAttackStage() + 1);
                    attacker.setSpecialDefenseStage(attacker.getSpecialDefenseStage() + 1);
                    attacker.setSpeedStage(attacker.getSpeedStage() + 1);
                }
                case "recover", "roost", "synthesis", "slack-off", "soft-boiled", "milk-drink", "moonlight", "shore-up" ->
                        attacker.setCurrentHp(Math.min(attacker.getMaxHp(), attacker.getCurrentHp() + (attacker.getMaxHp() / 2)));
                case "rest" -> attacker.setCurrentHp(attacker.getMaxHp());
                case "haze", "clear-smog" -> {
                    if (defender != null) {
                        defender.setAttackStage(0);
                        defender.setDefenseStage(0);
                        defender.setSpecialAttackStage(0);
                        defender.setSpecialDefenseStage(0);
                        defender.setSpeedStage(0);
                    }
                }
                default -> {
                }
            }
            return;
        }

        if (defender == null) {
            return;
        }

        int damage = estimateDamageAmount(move, attacker, defender);
        defender.setCurrentHp(Math.max(0, defender.getCurrentHp() - damage));
    }

    private int estimateDamageAmount(Move move, BattlePokemon attacker, BattlePokemon defender) {
        if (move == null || attacker == null || defender == null) {
            return 0;
        }
        double expectedPercent = estimateDamagePercent(move, attacker, defender);
        if (defender.getMaxHp() <= 0) {
            return 0;
        }
        return (int) Math.round((expectedPercent / 100.0) * defender.getMaxHp());
    }

    private double estimateDamagePercent(Move move, BattlePokemon attacker, BattlePokemon defender) {
        if (move == null || attacker == null) {
            return 0.0;
        }
        if (move.category() == MoveCategory.STATUS) {
            return 0.0;
        }

        double multiplier = typeMultiplier(move, defender);
        if (multiplier == 0.0) {
            return 0.0;
        }
        double stab = hasStab(attacker, move) ? 1.5 : 1.0;
        double attackStat = move.category() == MoveCategory.PHYSICAL ? stagedStat(attacker.getAttack(), attacker.getAttackStage())
                : stagedStat(attacker.getSpecialAttack(), attacker.getSpecialAttackStage());
        double defenseStat = move.category() == MoveCategory.PHYSICAL
                ? stagedStat(defender.getDefense(), defender.getDefenseStage())
                : stagedStat(defender.getSpecialDefense(), defender.getSpecialDefenseStage());
        double power = Math.max(move.basePower(), 1);
        double accuracy = move.accuracy() <= 0 ? 1.0 : move.accuracy() / 100.0;
        double offensiveRatio = defenseStat <= 0 ? attackStat : (attackStat / defenseStat);
        return power * accuracy * multiplier * stab * offensiveRatio * 5.5;
    }

    private double stagedStat(double baseStat, int stage) {
        int clamped = Math.max(-6, Math.min(6, stage));
        if (clamped >= 0) {
            return baseStat * (2.0 + clamped) / 2.0;
        }
        return baseStat * 2.0 / (2.0 - clamped);
    }

    private BattlePokemon copyForEvaluation(BattlePokemon pokemon) {
        if (pokemon == null) {
            return null;
        }
        return BattlePokemon.builder()
                .speciesId(pokemon.getSpeciesId())
                .originalSpeciesId(pokemon.getOriginalSpeciesId())
                .nickname(pokemon.getNickname())
                .level(pokemon.getLevel())
                .nature(pokemon.getNature())
                .ivs(pokemon.getIvs())
                .evs(pokemon.getEvs())
                .type1(pokemon.getType1())
                .type2(pokemon.getType2())
                .baseType1(pokemon.getBaseType1())
                .baseType2(pokemon.getBaseType2())
                .maxHp(pokemon.getMaxHp())
                .attack(pokemon.getAttack())
                .defense(pokemon.getDefense())
                .specialAttack(pokemon.getSpecialAttack())
                .specialDefense(pokemon.getSpecialDefense())
                .speed(pokemon.getSpeed())
                .currentHp(pokemon.getCurrentHp())
                .statusCondition(pokemon.getStatusCondition())
                .toxicCounter(pokemon.getToxicCounter())
                .sleepCounter(pokemon.getSleepCounter())
                .attackStage(pokemon.getAttackStage())
                .defenseStage(pokemon.getDefenseStage())
                .specialAttackStage(pokemon.getSpecialAttackStage())
                .specialDefenseStage(pokemon.getSpecialDefenseStage())
                .speedStage(pokemon.getSpeedStage())
                .evasionStage(pokemon.getEvasionStage())
                .accuracyStage(pokemon.getAccuracyStage())
                .moves(pokemon.getMoves())
                .ability(pokemon.getAbility())
                .heldItem(pokemon.getHeldItem())
                .choiceLock(pokemon.getChoiceLock())
                .build();
    }

    private Move getChoiceLockedMove(BattlePokemon active) {
        if (active.getChoiceLock() == null || active.getHeldItem() == null) {
            return null;
        }
        return switch (active.getHeldItem()) {
            case CHOICE_BAND, CHOICE_SPECS, CHOICE_SCARF -> active.getMoves().stream()
                    .filter(move -> move != null && move.id().equalsIgnoreCase(active.getChoiceLock()))
                    .findFirst()
                    .orElse(null);
            default -> null;
        };
    }

    private boolean shouldSwitchInsteadOfLockedMove(BattlePokemon active, BattlePokemon defender,
                                                    double forcedScore, String battleId,
                                                    String aiPlayerId, BattleSession session) {
        if (defender == null) {
            return false;
        }

        PlayerAction bestSwitch = chooseFallbackSwitch(battleId, aiPlayerId, session);
        if (!"SWITCH".equalsIgnoreCase(bestSwitch.actionType()) || bestSwitch.switchIndex() == null) {
            return false;
        }

        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon switchTarget = team.get(bestSwitch.switchIndex());
        if (switchTarget == null || switchTarget == active || switchTarget.isFainted()) {
            return false;
        }

        double switchScore = heuristicSwitchScore(switchTarget, defender);
        return forcedScore <= 0.0 || switchScore > forcedScore + 40.0;
    }

    private boolean shouldSwitchAgainstSetup(BattlePokemon active, BattlePokemon defender,
                                             Move bestMove, double bestMoveScore, String battleId,
                                             String aiPlayerId, BattleSession session) {
        if (defender == null || bestMove == null || defender.isFainted()) {
            return false;
        }
        int offensiveBoost = Math.max(defender.getAttackStage(), defender.getSpecialAttackStage());
        int speedBoost = defender.getSpeedStage();
        boolean defenderSnowballing = offensiveBoost >= 2 || speedBoost >= 2
                || (Math.max(0, offensiveBoost) + Math.max(0, speedBoost)) >= 3;
        if (!defenderSnowballing) {
            return false;
        }

        PlayerAction bestSwitch = chooseFallbackSwitch(battleId, aiPlayerId, session);
        if (!"SWITCH".equalsIgnoreCase(bestSwitch.actionType()) || bestSwitch.switchIndex() == null) {
            return false;
        }

        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon switchTarget = team.get(bestSwitch.switchIndex());
        if (switchTarget == null || switchTarget == active || switchTarget.isFainted()) {
            return false;
        }

        double switchScore = heuristicSwitchScore(switchTarget, defender);
        boolean weakIntoBoostedTarget = bestMove.category() == MoveCategory.STATUS || bestMoveScore < 90.0;
        return weakIntoBoostedTarget && switchScore > bestMoveScore + 25.0;
    }

    private boolean shouldTakeCompetitiveSwitch(BattlePokemon active, BattlePokemon defender,
                                                double currentPlanScore, PlayerAction bestSwitch,
                                                String aiPlayerId, BattleSession session) {
        if (defender == null || bestSwitch == null || !"SWITCH".equalsIgnoreCase(bestSwitch.actionType())
                || bestSwitch.switchIndex() == null) {
            return false;
        }
        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon switchTarget = team.get(bestSwitch.switchIndex());
        if (switchTarget == null || switchTarget == active || switchTarget.isFainted()) {
            return false;
        }

        double switchScore = competitiveSwitchScore(switchTarget, defender);
        double threat = estimateBestIncomingThreat(defender, active);
        boolean activeInDanger = threat >= 65.0 || active.getCurrentHp() <= Math.max(1, active.getMaxHp() / 3);
        boolean matchupAwful = currentPlanScore < 55.0;
        return switchScore > currentPlanScore + (activeInDanger ? 5.0 : 20.0)
                || (matchupAwful && switchScore > currentPlanScore)
                || (defenderHasMajorBoosts(defender) && switchScore > currentPlanScore - 5.0);
    }

    private double heuristicMoveScore(Move move, BattlePokemon attacker, BattlePokemon defender) {
        if (move.category() == MoveCategory.STATUS) {
            return move.priority() > 0 ? 25 : 5;
        }
        double power = Math.max(move.basePower(), 1);
        double accuracy = move.accuracy() <= 0 ? 1.0 : move.accuracy() / 100.0;
        double multiplier = 1.0;
        if (defender != null) {
            Type t2 = defender.getType2() == null ? Type.NONE : defender.getType2();
            multiplier = TypeChart.getMultiplier(move.type(), defender.getType1())
                    * TypeChart.getMultiplier(move.type(), t2);
        }
        double stab = 1.0;
        if (attacker != null && (attacker.getType1() == move.type() || attacker.getType2() == move.type())) {
            stab = 1.5;
        }
        return power * accuracy * multiplier * stab + (move.priority() * 20.0);
    }

    private double heuristicSwitchScore(BattlePokemon candidate, BattlePokemon opponent) {
        double hpRatio = candidate.getMaxHp() > 0 ? (double) candidate.getCurrentHp() / candidate.getMaxHp() : 0.0;
        double bulk = candidate.getDefense() + candidate.getSpecialDefense();
        double speed = candidate.getSpeed();
        double offensivePressure = 0.0;
        double defensiveSafety = 1.0;

        if (opponent != null) {
            offensivePressure = candidate.getMoves().stream()
                    .filter(move -> move != null)
                    .mapToDouble(move -> heuristicMoveScore(move, candidate, opponent))
                    .max()
                    .orElse(0.0);

            defensiveSafety = 0.0;
            for (Move opponentMove : opponent.getMoves()) {
                if (opponentMove == null) {
                    continue;
                }
                Type candidateType2 = candidate.getType2() == null ? Type.NONE : candidate.getType2();
                double multiplier = TypeChart.getMultiplier(opponentMove.type(), candidate.getType1())
                        * TypeChart.getMultiplier(opponentMove.type(), candidateType2);
                defensiveSafety = Math.max(defensiveSafety, multiplier);
            }
        }

        return offensivePressure
                + (hpRatio * 140.0)
                + (bulk * 0.12)
                + (speed * 0.08)
                - (defensiveSafety * 90.0);
    }

    private double competitiveMoveScore(Move move, BattlePokemon attacker, BattlePokemon defender) {
        if (move == null || attacker == null) {
            return Double.NEGATIVE_INFINITY;
        }
        if (move.category() == MoveCategory.STATUS) {
            return competitiveStatusScore(move, attacker, defender);
        }

        double multiplier = typeMultiplier(move, defender);
        if (multiplier == 0.0) {
            return -1000.0;
        }
        double stab = hasStab(attacker, move) ? 1.5 : 1.0;
        double attackStat = move.category() == MoveCategory.PHYSICAL ? attacker.getAttack() : attacker.getSpecialAttack();
        double defenseStat = move.category() == MoveCategory.PHYSICAL && defender != null ? defender.getDefense()
                : defender != null ? defender.getSpecialDefense() : 100.0;
        double power = Math.max(move.basePower(), 1);
        double accuracy = move.accuracy() <= 0 ? 1.0 : move.accuracy() / 100.0;
        double priority = move.priority() * 18.0;
        double offensiveRatio = defenseStat <= 0 ? attackStat : (attackStat / defenseStat);
        double expectedDamagePercent = power * accuracy * multiplier * stab * offensiveRatio * 5.5;

        double score = expectedDamagePercent + priority;
        if (defender != null) {
            double defenderHpPercent = defender.getMaxHp() > 0 ? (defender.getCurrentHp() * 100.0 / defender.getMaxHp()) : 100.0;
            if (expectedDamagePercent >= defenderHpPercent) {
                score += 140.0;
                if (move.priority() > 0) {
                    score += 40.0;
                }
            }
            if (multiplier >= 2.0) {
                score += 28.0;
            } else if (multiplier < 1.0) {
                score -= 25.0;
            }
            if (defenderHasMajorBoosts(defender) && move.priority() > 0) {
                score += 30.0;
            }
        }
        return score;
    }

    private double competitiveStatusScore(Move move, BattlePokemon attacker, BattlePokemon defender) {
        String moveId = move.id().toLowerCase();
        double hpRatio = attacker.getMaxHp() > 0 ? (double) attacker.getCurrentHp() / attacker.getMaxHp() : 0.0;
        return switch (moveId) {
            case "dragon-dance", "swords-dance", "nasty-plot", "calm-mind", "quiver-dance", "belly-drum" -> {
                double base = hpRatio > 0.65 ? 90.0 : 30.0;
                if (defender != null && estimateBestIncomingThreat(defender, attacker) < 45.0) {
                    base += 35.0;
                }
                yield base;
            }
            case "recover", "roost", "synthesis", "slack-off", "soft-boiled", "rest", "milk-drink", "moonlight", "shore-up" -> {
                double missingHpPercent = 100.0 - (hpRatio * 100.0);
                double base = missingHpPercent;
                if (defender != null && estimateBestIncomingThreat(defender, attacker) > 55.0) {
                    base -= 35.0;
                }
                yield base;
            }
            case "protect", "kings-shield", "spiky-shield" -> 20.0;
            case "haze", "clear-smog" -> defenderHasMajorBoosts(defender) ? 120.0 : 25.0;
            case "thunder-wave", "will-o-wisp", "toxic", "sleep-powder" -> 55.0;
            case "spikes", "stealth-rock", "sticky-web", "toxic-spikes" -> 50.0;
            default -> 18.0 + move.priority() * 8.0;
        };
    }

    private double competitiveSwitchScore(BattlePokemon candidate, BattlePokemon opponent) {
        double hpRatio = candidate.getMaxHp() > 0 ? (double) candidate.getCurrentHp() / candidate.getMaxHp() : 0.0;
        double bestOutgoing = candidate.getMoves().stream()
                .filter(move -> move != null)
                .mapToDouble(move -> competitiveMoveScore(move, candidate, opponent))
                .max()
                .orElse(-50.0);
        double incomingThreat = estimateBestIncomingThreat(opponent, candidate);
        double speedValue = candidate.getSpeed() * 0.07;
        double bulkValue = (candidate.getDefense() + candidate.getSpecialDefense()) * 0.08;
        return bestOutgoing + (hpRatio * 110.0) + speedValue + bulkValue - incomingThreat;
    }

    private double estimateBestIncomingThreat(BattlePokemon attacker, BattlePokemon defender) {
        if (attacker == null || defender == null) {
            return 0.0;
        }
        return attacker.getMoves().stream()
                .filter(move -> move != null)
                .mapToDouble(move -> {
                    if (move.category() == MoveCategory.STATUS) {
                        return defenderHasMajorBoosts(attacker) ? 35.0 : 10.0;
                    }
                    double multiplier = typeMultiplier(move, defender);
                    double stab = hasStab(attacker, move) ? 1.5 : 1.0;
                    double attackStat = move.category() == MoveCategory.PHYSICAL ? attacker.getAttack() : attacker.getSpecialAttack();
                    double defenseStat = move.category() == MoveCategory.PHYSICAL ? defender.getDefense() : defender.getSpecialDefense();
                    double offensiveRatio = defenseStat <= 0 ? attackStat : (attackStat / defenseStat);
                    return Math.max(move.basePower(), 1) * multiplier * stab * offensiveRatio * 5.2;
                })
                .max()
                .orElse(0.0);
    }

    private boolean defenderHasMajorBoosts(BattlePokemon defender) {
        if (defender == null) {
            return false;
        }
        return defender.getAttackStage() >= 2
                || defender.getSpecialAttackStage() >= 2
                || defender.getSpeedStage() >= 2
                || (Math.max(0, defender.getAttackStage()) + Math.max(0, defender.getSpecialAttackStage())
                + Math.max(0, defender.getSpeedStage())) >= 3;
    }

    private boolean hasStab(BattlePokemon attacker, Move move) {
        return attacker != null && (attacker.getType1() == move.type() || attacker.getType2() == move.type());
    }

    private double typeMultiplier(Move move, BattlePokemon defender) {
        if (move == null || defender == null) {
            return 1.0;
        }
        Type t2 = defender.getType2() == null ? Type.NONE : defender.getType2();
        return TypeChart.getMultiplier(move.type(), defender.getType1())
                * TypeChart.getMultiplier(move.type(), t2);
    }

    private record BenchPokemon(int teamIndex, BattlePokemon pokemon) {
    }

    private static final class SearchState {
        private BattlePokemon aiActive;
        private List<BenchPokemon> aiBench;
        private BattlePokemon opponentActive;
        private List<BenchPokemon> opponentBench;
        private boolean aiTurn;

        private SearchState(BattlePokemon aiActive, List<BenchPokemon> aiBench,
                            BattlePokemon opponentActive, List<BenchPokemon> opponentBench,
                            boolean aiTurn) {
            this.aiActive = aiActive;
            this.aiBench = aiBench;
            this.opponentActive = opponentActive;
            this.opponentBench = opponentBench;
            this.aiTurn = aiTurn;
        }

        public BattlePokemon aiActive() { return aiActive; }
        public List<BenchPokemon> aiBench() { return aiBench; }
        public BattlePokemon opponentActive() { return opponentActive; }
        public List<BenchPokemon> opponentBench() { return opponentBench; }
        public boolean aiTurn() { return aiTurn; }
        public void setAiActive(BattlePokemon aiActive) { this.aiActive = aiActive; }
        public void setOpponentActive(BattlePokemon opponentActive) { this.opponentActive = opponentActive; }
        public void setAiTurn(boolean aiTurn) { this.aiTurn = aiTurn; }
    }

    private record SearchAction(String actionType, String moveId, Integer switchIndex, double orderingScore) {
        private static SearchAction move(String moveId, double orderingScore) {
            return new SearchAction("MOVE", moveId, null, orderingScore);
        }

        private static SearchAction switchTo(int switchIndex, double orderingScore) {
            return new SearchAction("SWITCH", null, switchIndex, orderingScore);
        }

        private boolean isSwitch() {
            return "SWITCH".equals(actionType);
        }
    }
}
