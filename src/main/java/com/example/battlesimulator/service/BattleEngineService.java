package com.example.battlesimulator.service;

import com.example.battlesimulator.dto.BattleInitRequest;
import com.example.battlesimulator.dto.PlayerAction;
import com.example.battlesimulator.dto.TurnResult;
import com.example.battlesimulator.dto.TurnResult.PokemonSnapshot;
import com.example.battlesimulator.model.BattlePokemon;
import com.example.battlesimulator.model.BattleSession;
import com.example.battlesimulator.model.BattleSession.Phase;
import com.example.battlesimulator.model.Move;
import com.example.battlesimulator.model.enums.Ability;
import com.example.battlesimulator.model.enums.HeldItem;
import com.example.battlesimulator.model.enums.Terrain;
import com.example.battlesimulator.model.enums.Type;
import com.example.battlesimulator.model.enums.Weather;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BattleEngineService {

    private final DamageCalculatorService damageCalculator;
    private final AccuracyService accuracyService;
    private final PokeApiService pokeApiService;
    private final GroqBattleAiService groqBattleAiService;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, BattleSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, List<PlayerAction>> pendingActions = new ConcurrentHashMap<>();
    private final java.util.Random rng = new java.util.Random();
    private static final Map<HeldItem, MegaEvolutionData> MEGA_EVOLUTIONS = Map.ofEntries(
            Map.entry(HeldItem.VENUSAURITE, new MegaEvolutionData("venusaur", "venusaur-mega", Ability.THICK_FAT)),
            Map.entry(HeldItem.CHARIZARDITE_X, new MegaEvolutionData("charizard", "charizard-mega-x", Ability.TOUGH_CLAWS)),
            Map.entry(HeldItem.CHARIZARDITE_Y, new MegaEvolutionData("charizard", "charizard-mega-y", Ability.DROUGHT)),
            Map.entry(HeldItem.BLASTOISINITE, new MegaEvolutionData("blastoise", "blastoise-mega", Ability.MEGA_LAUNCHER)),
            Map.entry(HeldItem.ALAKAZITE, new MegaEvolutionData("alakazam", "alakazam-mega", Ability.TRACE)),
            Map.entry(HeldItem.GARDEVOIRITE, new MegaEvolutionData("gardevoir", "gardevoir-mega", Ability.PIXILATE)),
            Map.entry(HeldItem.MAWILITE, new MegaEvolutionData("mawile", "mawile-mega", Ability.HUGE_POWER)),
            Map.entry(HeldItem.LUCARIONITE, new MegaEvolutionData("lucario", "lucario-mega", Ability.ADAPTABILITY)),
            Map.entry(HeldItem.SCIZORITE, new MegaEvolutionData("scizor", "scizor-mega", Ability.TECHNICIAN)),
            Map.entry(HeldItem.TYRANITARITE, new MegaEvolutionData("tyranitar", "tyranitar-mega", Ability.SAND_STREAM)),
            Map.entry(HeldItem.GARCHOMPITE, new MegaEvolutionData("garchomp", "garchomp-mega", Ability.SAND_FORCE)),
            Map.entry(HeldItem.SALAMENCITE, new MegaEvolutionData("salamence", "salamence-mega", Ability.AERILATE)),
            Map.entry(HeldItem.SABLENITE, new MegaEvolutionData("sableye", "sableye-mega", Ability.MAGIC_BOUNCE)),
            Map.entry(HeldItem.METAGROSSITE, new MegaEvolutionData("metagross", "metagross-mega", Ability.TOUGH_CLAWS))
    );

    private record MegaEvolutionData(String baseSpecies, String megaSpecies, Ability megaAbility) {}

    public BattleEngineService(DamageCalculatorService damageCalculator,
                               AccuracyService accuracyService,
                               PokeApiService pokeApiService,
                               GroqBattleAiService groqBattleAiService,
                               SimpMessagingTemplate messagingTemplate) {
        this.damageCalculator = damageCalculator;
        this.accuracyService = accuracyService;
        this.pokeApiService = pokeApiService;
        this.groqBattleAiService = groqBattleAiService;
        this.messagingTemplate = messagingTemplate;
    }

    // -------------------------------------------------------------------------
    // SETUP
    // -------------------------------------------------------------------------

    public synchronized TurnResult initializePokemon(BattleInitRequest request) {
        // Slot 0 = fresh lock-in: reset this player's team so stale slots don't linger
        if (request.pokemonSlot() == 0) {
            BattleSession existing = activeSessions.get(request.battleId());
            if (existing != null && existing.getPhase() != Phase.WAITING_FOR_PLAYERS) {
                // Battle was in progress — full room reset so both players can re-queue
                activeSessions.remove(request.battleId());
                pendingActions.remove(request.battleId());
            } else if (existing != null) {
                // Still in setup — just wipe this player's old slots
                if (request.playerId().equals("player1")) existing.getPlayer1Team().clear();
                else existing.getPlayer2Team().clear();
                existing.getBattleHistory().clear();
                existing.setWinnerId(null);
                existing.setTurnCount(1);
                existing.setPhase(Phase.WAITING_FOR_PLAYERS);
            }
        }

        activeSessions.putIfAbsent(request.battleId(), new BattleSession(request.battleId()));
        pendingActions.putIfAbsent(request.battleId(), new ArrayList<>());
        BattleSession session = activeSessions.get(request.battleId());
        if (request.aiControlled()) {
            session.setAiBattle(true);
            session.setAiPlayerId(request.playerId());
            session.setAiMode(request.aiMode());
        }

        BattlePokemon pokemon = pokeApiService.createBattlePokemon(
                request.speciesName(), request.level(),
                request.ivs(), request.evs(), request.nature());

        List<Move> moves = request.moveNames().stream()
                .map(pokeApiService::getMove)
                .toList();
        pokemon.setMoves(moves);
        if (request.heldItem() != null) {
            pokemon.setHeldItem(request.heldItem());
        }
        if (request.ability() != null) {
            pokemon.setAbility(request.ability());
        }
        String prefix = request.playerId().equals("player1") ? "P1-" : "P2-";
        pokemon.setNickname(prefix + capitalize(request.speciesName()));

        List<BattlePokemon> team = request.playerId().equals("player1")
                ? session.getPlayer1Team() : session.getPlayer2Team();

        // Ensure list is large enough, fill gaps with null
        while (team.size() <= request.pokemonSlot()) team.add(null);
        team.set(request.pokemonSlot(), pokemon);

        System.out.printf("Slot %d set for %s in room %s: %s%n",
                request.pokemonSlot(), request.playerId(), request.battleId(), pokemon.getNickname());

        // Build snapshot to return immediately (setup ack)
        TurnResult ack = buildSnapshot(session, List.of("Registered " + pokemon.getNickname() + " for " + request.playerId()), false);
        broadcast(request.battleId(), ack);

        // If both teams are fully registered (6 non-null slots each), start the battle
        if (session.areBothTeamsReady()) {
            session.setPhase(Phase.CHOOSING_ACTION);
            List<String> startLog = new ArrayList<>();
            startLog.add("⚔️ Both teams ready! Battle begins!");
            // Apply on-enter abilities for both starting Pokémon
            BattlePokemon p1Start = session.getPlayer1Active();
            BattlePokemon p2Start = session.getPlayer2Active();
            tryApplyMegaEvolution(p1Start, startLog);
            tryApplyMegaEvolution(p2Start, startLog);
            applyOnSwitchInAbility(p1Start, p2Start, session, startLog);
            applyOnSwitchInAbility(p2Start, p1Start, session, startLog);
            startLog.add("Turn 1 — choose your moves!");
            TurnResult startResult = buildSnapshot(session, startLog, false);
            broadcast(request.battleId(), startResult);
            return startResult;
        }

        return ack;
    }

    public synchronized TurnResult getBattleSnapshot(String battleId) {
        BattleSession session = activeSessions.get(battleId);
        if (session == null) {
            return null;
        }
        return buildSnapshot(session, List.of(), session.getPhase() == Phase.BATTLE_OVER);
    }

    // -------------------------------------------------------------------------
    // ACTION HANDLING (called from WebSocket @MessageMapping)
    // -------------------------------------------------------------------------

    public synchronized void handleAction(PlayerAction action) {
        BattleSession session = activeSessions.get(action.battleId());
        if (session == null || session.getPhase() == Phase.BATTLE_OVER) return;

        Phase phase = session.getPhase();

        if (action.actionType().equals("SWITCH")) {
            if (phase == Phase.CHOOSING_ACTION) {
                // Trap check — cannot voluntarily switch out if trapped
                BattlePokemon currentActive = action.playerId().equals("player1")
                        ? session.getPlayer1Active() : session.getPlayer2Active();
                if (currentActive != null && currentActive.isTrapped()) {
                    broadcast(action.battleId(), buildSnapshot(session,
                            List.of(currentActive.getNickname() + " is trapped and cannot switch out!"), false));
                    return;
                }
                // Voluntary switch — register as a pending action so it costs a turn
                // and waits for the opponent to also submit their action.
                registerPendingAction(action);
                maybeQueueAiAction(session, action.battleId(), action.playerId());
                if (bothPlayersActed(action.battleId())) {
                    resolveTurn(action.battleId());
                } else {
                    broadcast(action.battleId(), buildSnapshot(session,
                            List.of(action.playerId() + " is switching. Waiting for opponent..."), false));
                }
            } else {
                // Forced switch (post-faint) — execute immediately, no turn cost.
                handleSwitch(session, action, phase);
            }
        } else if (action.actionType().equals("MOVE")) {
            if (phase != Phase.CHOOSING_ACTION) return; // ignore moves while in switch phase
            registerPendingAction(action);
            maybeQueueAiAction(session, action.battleId(), action.playerId());
            if (bothPlayersActed(action.battleId())) {
                resolveTurn(action.battleId());
            } else {
                broadcast(action.battleId(), buildSnapshot(session,
                        List.of(action.playerId() + " locked in a move. Waiting for opponent..."), false));
            }
        }
    }

    private void handleSwitch(BattleSession session, PlayerAction action, Phase phase) {
        int idx = action.switchIndex();
        List<BattlePokemon> team = action.playerId().equals("player1")
                ? session.getPlayer1Team() : session.getPlayer2Team();

        if (idx < 0 || idx >= team.size() || team.get(idx) == null || team.get(idx).isFainted()) {
            broadcast(action.battleId(), buildSnapshot(session,
                    List.of("Invalid switch target. Pick a non-fainted Pokémon."), false));
            return;
        }
        // Trapping check — cannot switch out if trapped (unless forced faint switch)
        BattlePokemon currentActive = action.playerId().equals("player1")
                ? session.getPlayer1Active() : session.getPlayer2Active();
        if (currentActive != null && currentActive.isTrapped() && !currentActive.isFainted()
                && phase == Phase.CHOOSING_ACTION) {
            broadcast(action.battleId(), buildSnapshot(session,
                    List.of(currentActive.getNickname() + " is trapped and cannot switch out!"), false));
            return;
        }

        List<String> log = new ArrayList<>();
        if (action.playerId().equals("player1")) {
            BattlePokemon prev = session.getPlayer1Active();
            session.setPlayer1ActiveIndex(idx);
            BattlePokemon incoming = session.getPlayer1Active();
            log.add("Player 1 sent out " + incoming.getNickname() + "!");
            if (prev != null && !prev.isFainted()) {
                log.add("(" + prev.getNickname() + " was withdrawn)");
                applyOnSwitchOutAbility(prev, log);
            }
            tryApplyMegaEvolution(incoming, log);
            applyOnSwitchInAbility(incoming, session.getPlayer2Active(), session, log);
            applyEntryHazards(incoming, 1, session, log);
        } else {
            BattlePokemon prev = session.getPlayer2Active();
            session.setPlayer2ActiveIndex(idx);
            BattlePokemon incoming = session.getPlayer2Active();
            log.add("Player 2 sent out " + incoming.getNickname() + "!");
            if (prev != null && !prev.isFainted()) {
                log.add("(" + prev.getNickname() + " was withdrawn)");
                applyOnSwitchOutAbility(prev, log);
            }
            tryApplyMegaEvolution(incoming, log);
            applyOnSwitchInAbility(incoming, session.getPlayer1Active(), session, log);
            applyEntryHazards(incoming, 2, session, log);
        }

        // Update phase after switch
        Phase newPhase = resolvePhaseAfterSwitch(session, phase, action.playerId());
        session.setPhase(newPhase);

        if (newPhase == Phase.CHOOSING_ACTION) {
            log.add("Turn " + session.getTurnCount() + " — both active. Choose your moves!");
        }

        broadcast(action.battleId(), buildSnapshot(session, log, false));

        // If we transitioned back to CHOOSING_ACTION and there were pending moves queued
        // (one player chose a move before the other had to switch), resolve now
        if (newPhase == Phase.CHOOSING_ACTION && bothPlayersActed(action.battleId())) {
            resolveTurn(action.battleId());
        }

        maybeHandleAiForcedSwitch(session, action.battleId());
    }

    private Phase resolvePhaseAfterSwitch(BattleSession session, Phase phase, String switchingPlayer) {
        return switch (phase) {
            case WAITING_FOR_SWITCH_P1 -> Phase.CHOOSING_ACTION;
            case WAITING_FOR_SWITCH_P2 -> Phase.CHOOSING_ACTION;
            case WAITING_FOR_SWITCH_BOTH -> {
                // The other player still needs to switch
                yield switchingPlayer.equals("player1")
                        ? Phase.WAITING_FOR_SWITCH_P2
                        : Phase.WAITING_FOR_SWITCH_P1;
            }
            default -> Phase.CHOOSING_ACTION;
        };
    }

    // -------------------------------------------------------------------------
    // TURN RESOLUTION
    // -------------------------------------------------------------------------

    private void resolveTurn(String battleId) {
        BattleSession session = activeSessions.get(battleId);
        List<PlayerAction> actions = pendingActions.get(battleId);
        List<String> log = new ArrayList<>();

        log.add("--- Turn " + session.getTurnCount() + " ---");

        PlayerAction p1Action = actions.stream().filter(a -> a.playerId().equals("player1")).findFirst().orElse(null);
        PlayerAction p2Action = actions.stream().filter(a -> a.playerId().equals("player2")).findFirst().orElse(null);

        BattlePokemon p1 = session.getPlayer1Active();
        BattlePokemon p2 = session.getPlayer2Active();
        p1.setMovedThisTurn(false);
        p2.setMovedThisTurn(false);
        p1.setAnalyticBoosted(false);
        p2.setAnalyticBoosted(false);

        // Handle any switches chosen as the action this turn (voluntary switch)
        if (p1Action != null && p1Action.actionType().equals("SWITCH")) {
            int idx = p1Action.switchIndex();
            BattlePokemon p1Prev = session.getPlayer1Active();
            if (p1Prev != null) applyOnSwitchOutAbility(p1Prev, log);
            session.setPlayer1ActiveIndex(idx);
            p1 = session.getPlayer1Active();
            log.add("Player 1 switched to " + p1.getNickname() + "!");
            tryApplyMegaEvolution(p1, log);
            applyOnSwitchInAbility(p1, session.getPlayer2Active(), session, log);
            applyEntryHazards(p1, 1, session, log);
        }
        if (p2Action != null && p2Action.actionType().equals("SWITCH")) {
            int idx = p2Action.switchIndex();
            BattlePokemon p2Prev = session.getPlayer2Active();
            if (p2Prev != null) applyOnSwitchOutAbility(p2Prev, log);
            session.setPlayer2ActiveIndex(idx);
            p2 = session.getPlayer2Active();
            log.add("Player 2 switched to " + p2.getNickname() + "!");
            tryApplyMegaEvolution(p2, log);
            applyOnSwitchInAbility(p2, session.getPlayer1Active(), session, log);
            applyEntryHazards(p2, 2, session, log);
        }

        Move p1Move = (p1Action != null && p1Action.actionType().equals("MOVE"))
                ? pokeApiService.getMove(p1Action.targetId()) : null;
        Move p2Move = (p2Action != null && p2Action.actionType().equals("MOVE"))
                ? pokeApiService.getMove(p2Action.targetId()) : null;

        // Determine move order: priority bracket first, Speed tiebreak within same bracket.
        // Switches always go before moves (treated as priority +7 here).
        int p1Priority = (p1Action != null && p1Action.actionType().equals("SWITCH"))
                ? 7 : adjustedPriority(p1, p1Move, session);
        int p2Priority = (p2Action != null && p2Action.actionType().equals("SWITCH"))
                ? 7 : adjustedPriority(p2, p2Move, session);

        // Apply stat-stage speed multiplier for accurate tiebreaking.
        // Paralysis halves effective speed (Gen 7+ behaviour).
        double p1ParaFactor = (p1.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS) ? 0.5 : 1.0;
        double p2ParaFactor = (p2.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS) ? 0.5 : 1.0;
        double p1ScarfFactor  = (p1.getHeldItem() == HeldItem.CHOICE_SCARF)  ? 1.5 : 1.0;
        double p2ScarfFactor  = (p2.getHeldItem() == HeldItem.CHOICE_SCARF)  ? 1.5 : 1.0;
        double p1IronBallFactor = (p1.getHeldItem() == HeldItem.IRON_BALL)   ? 0.5 : 1.0;
        double p2IronBallFactor = (p2.getHeldItem() == HeldItem.IRON_BALL)   ? 0.5 : 1.0;
        double p1AbilitySpeedFactor = abilitySpeedFactor(p1, session);
        double p2AbilitySpeedFactor = abilitySpeedFactor(p2, session);
        int p1EffectiveSpeed = (int) Math.floor(p1.getSpeed() * stageMultiplier(p1.getSpeedStage()) * p1ParaFactor * p1ScarfFactor * p1IronBallFactor * p1AbilitySpeedFactor);
        int p2EffectiveSpeed = (int) Math.floor(p2.getSpeed() * stageMultiplier(p2.getSpeedStage()) * p2ParaFactor * p2ScarfFactor * p2IronBallFactor * p2AbilitySpeedFactor);

        boolean p1GoesFirst;
        if (p1Priority != p2Priority) {
            p1GoesFirst = p1Priority > p2Priority;  // higher priority bracket always goes first
        } else {
            int p1SpeedForOrder = p1.getAbility() == Ability.STALL ? Integer.MIN_VALUE : p1EffectiveSpeed;
            int p2SpeedForOrder = p2.getAbility() == Ability.STALL ? Integer.MIN_VALUE : p2EffectiveSpeed;
            p1GoesFirst = p1SpeedForOrder != p2SpeedForOrder
                    ? p1SpeedForOrder > p2SpeedForOrder          // faster mon goes first
                    : rng.nextBoolean();                           // true speed tie: 50/50 coin flip
        }

        if (p1Priority != p2Priority) {
            log.add("(" + (p1GoesFirst ? p1.getNickname() : p2.getNickname())
                    + " has priority " + (p1GoesFirst ? p1Priority : p2Priority) + " vs "
                    + (p1GoesFirst ? p2Priority : p1Priority) + " — moves first!)");
        } else if (p1EffectiveSpeed == p2EffectiveSpeed) {
            log.add("(" + (p1GoesFirst ? p1.getNickname() : p2.getNickname()) + " wins the speed tie!)");
        }

        if (p1GoesFirst) {
            if (p1Move != null) executeAttack(p1, p2, p1Move, session, log);
            if (!p2.isFainted() && p2Move != null) executeAttack(p2, p1, p2Move, session, log);
        } else {
            if (p2Move != null) executeAttack(p2, p1, p2Move, session, log);
            if (!p1.isFainted() && p1Move != null) executeAttack(p1, p2, p1Move, session, log);
        }

        // ── Protect: clear for next turn ─────────────────────────────────
        if (p1.isProtecting()) { p1.setProtecting(false); }
        else                   { p1.setProtectConsecutive(0); } // reset streak if didn't protect
        if (p2.isProtecting()) { p2.setProtecting(false); }
        else                   { p2.setProtectConsecutive(0); }

        // ── U-turn / Volt Switch / Flip Turn / Baton Pass: forced switch ──
        if (!p1.isFainted() && p1.isNeedsForcedSwitch()) {
            p1.setNeedsForcedSwitch(false);
            int nextP1 = session.getNextAliveIndex(1);
            if (nextP1 >= 0) {
                if (p1.isBatonPassPending()) {
                    // Copy stat stages to incoming
                    applyOnSwitchOutAbility(p1, log);
                    int[] stages = { p1.getAttackStage(), p1.getDefenseStage(),
                            p1.getSpecialAttackStage(), p1.getSpecialDefenseStage(),
                            p1.getSpeedStage(), p1.getEvasionStage(), p1.getAccuracyStage() };
                    p1.setBatonPassPending(false);
                    session.setPlayer1ActiveIndex(nextP1);
                    p1 = session.getPlayer1Active();
                    p1.setAttackStage(stages[0]); p1.setDefenseStage(stages[1]);
                    p1.setSpecialAttackStage(stages[2]); p1.setSpecialDefenseStage(stages[3]);
                    p1.setSpeedStage(stages[4]); p1.setEvasionStage(stages[5]); p1.setAccuracyStage(stages[6]);
                    log.add("Player 1 passed stats via Baton Pass to " + p1.getNickname() + "!");
                } else {
                    session.setPhase(Phase.WAITING_FOR_SWITCH_P1);
                    log.add("Player 1 must send in a new Pokémon after " + p1.getNickname() + "!");
                }
            }
        }
        if (!p2.isFainted() && p2.isNeedsForcedSwitch()) {
            p2.setNeedsForcedSwitch(false);
            int nextP2 = session.getNextAliveIndex(2);
            if (nextP2 >= 0) {
                if (p2.isBatonPassPending()) {
                    applyOnSwitchOutAbility(p2, log);
                    int[] stages = { p2.getAttackStage(), p2.getDefenseStage(),
                            p2.getSpecialAttackStage(), p2.getSpecialDefenseStage(),
                            p2.getSpeedStage(), p2.getEvasionStage(), p2.getAccuracyStage() };
                    p2.setBatonPassPending(false);
                    session.setPlayer2ActiveIndex(nextP2);
                    p2 = session.getPlayer2Active();
                    p2.setAttackStage(stages[0]); p2.setDefenseStage(stages[1]);
                    p2.setSpecialAttackStage(stages[2]); p2.setSpecialDefenseStage(stages[3]);
                    p2.setSpeedStage(stages[4]); p2.setEvasionStage(stages[5]); p2.setAccuracyStage(stages[6]);
                    log.add("Player 2 passed stats via Baton Pass to " + p2.getNickname() + "!");
                } else {
                    session.setPhase(Phase.WAITING_FOR_SWITCH_P2);
                    log.add("Player 2 must send in a new Pokémon after " + p2.getNickname() + "!");
                }
            }
        }

        // ── Future Sight / Doom Desire: tick down and land ────────────────
        if (!p1.isFainted() && p1.getFutureSightTurns() > 0) {
            int t = p1.getFutureSightTurns() - 1;
            p1.setFutureSightTurns(t);
            if (t == 0) {
                int dmg = p1.getFutureSightDmg();
                p2.setCurrentHp(Math.max(0, p2.getCurrentHp() - dmg));
                log.add("The Future Sight attack struck " + p2.getNickname() + "! (−" + dmg + " HP)");
                p1.setFutureSightDmg(0);
            }
        }
        if (!p2.isFainted() && p2.getFutureSightTurns() > 0) {
            int t = p2.getFutureSightTurns() - 1;
            p2.setFutureSightTurns(t);
            if (t == 0) {
                int dmg = p2.getFutureSightDmg();
                p1.setCurrentHp(Math.max(0, p1.getCurrentHp() - dmg));
                log.add("The Future Sight attack struck " + p1.getNickname() + "! (−" + dmg + " HP)");
                p2.setFutureSightDmg(0);
            }
        }

        // ── Perish Song countdown ─────────────────────────────────────────
        for (BattlePokemon mon : List.of(p1, p2)) {
            if (!mon.isFainted() && mon.getPerishCount() > 0) {
                int pc = mon.getPerishCount() - 1;
                mon.setPerishCount(pc);
                if (pc == 0) {
                    mon.setCurrentHp(0);
                    log.add(mon.getNickname() + "'s Perish Song countdown reached 0! It fainted!");
                } else {
                    log.add(mon.getNickname() + "'s Perish Song count: " + pc + "!");
                }
            }
        }

        // End-of-turn status damage (burn, poison, toxic)
        if (!p1.isFainted()) applyEndOfTurnStatus(p1, log);
        if (!p2.isFainted()) applyEndOfTurnStatus(p2, log);

        // End-of-turn ability effects (Speed Boost, Solar Power, Poison Heal, etc.)
        if (!p1.isFainted()) applyEndOfTurnAbilityEffects(p1, session, log);
        if (!p2.isFainted()) applyEndOfTurnAbilityEffects(p2, session, log);

        // End-of-turn held item effects
        if (!p1.isFainted()) applyEndOfTurnItemEffects(p1, log);
        if (!p2.isFainted()) applyEndOfTurnItemEffects(p2, log);

        // End-of-turn volatile condition countdowns (taunt)
        if (!p1.isFainted()) tickVolatileConditions(p1, log);
        if (!p2.isFainted()) tickVolatileConditions(p2, log);

        // End-of-turn weather damage + expiry
        applyEndOfTurnWeather(session, p1, p2, log);

        // Faint checks
        boolean p1Fainted = p1.isFainted();
        boolean p2Fainted = p2.isFainted();

        if (p1Fainted) log.add(p1.getNickname() + " fainted!");
        if (p2Fainted) log.add(p2.getNickname() + " fainted!");

        // If a trapping Pokemon faints, release the opponent's trap
        if (p1Fainted && hasTrappingAbility(p1)) p2.setTrapped(false);
        if (p2Fainted && hasTrappingAbility(p2)) p1.setTrapped(false);

        // Win condition
        if (session.isPlayer1AllFainted()) {
            session.setPhase(Phase.BATTLE_OVER);
            session.setWinnerId("player2");
            log.add("🏆 Player 2 wins the battle!");
        } else if (session.isPlayer2AllFainted()) {
            session.setPhase(Phase.BATTLE_OVER);
            session.setWinnerId("player1");
            log.add("🏆 Player 1 wins the battle!");
        } else if (p1Fainted && p2Fainted) {
            session.setPhase(Phase.WAITING_FOR_SWITCH_BOTH);
            log.add("Both Pokémon fainted! Both players must send out a new one.");
        } else if (p1Fainted) {
            session.setPhase(Phase.WAITING_FOR_SWITCH_P1);
            log.add("Player 1 must send out a new Pokémon.");
        } else if (p2Fainted) {
            session.setPhase(Phase.WAITING_FOR_SWITCH_P2);
            log.add("Player 2 must send out a new Pokémon.");
        } else {
            session.setPhase(Phase.CHOOSING_ACTION);
            session.setTurnCount(session.getTurnCount() + 1);
            log.add("Turn " + session.getTurnCount() + " — choose your moves!");
        }

        pendingActions.put(battleId, new ArrayList<>());

        boolean battleOver = session.getPhase() == Phase.BATTLE_OVER;
        broadcast(battleId, buildSnapshot(session, log, battleOver));
        maybeHandleAiForcedSwitch(session, battleId);
    }

    private void executeAttack(BattlePokemon attacker, BattlePokemon defender, Move move, BattleSession session, List<String> log) {
        // ── Choice item lock ──────────────────────────────────────────────
        HeldItem attackerItem = attacker.getHeldItem();
        if ((attackerItem == HeldItem.CHOICE_BAND || attackerItem == HeldItem.CHOICE_SPECS
                || attackerItem == HeldItem.CHOICE_SCARF)) {
            if (attacker.getChoiceLock() == null) {
                attacker.setChoiceLock(move.id());
            } else if (!attacker.getChoiceLock().equals(move.id())) {
                Move lockedMove = pokeApiService.getMove(attacker.getChoiceLock());
                if (lockedMove == null) {
                    log.add(attacker.getNickname() + " is locked into " + attacker.getChoiceLock()
                            + " by its " + attackerItem.getDisplayName() + " and can't use " + move.name() + "!");
                    return;
                }
                log.add(attacker.getNickname() + " is locked into " + lockedMove.id()
                        + " by its " + attackerItem.getDisplayName() + " and is forced to keep using it!");
                move = lockedMove;
            }
        }

        // ── Assault Vest blocks status moves ─────────────────────────────
        if (defender.getHeldItem() == HeldItem.ASSAULT_VEST
                && move.category() == com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            log.add(attacker.getNickname() + " used " + move.name() + "!");
            log.add(defender.getNickname() + "'s Assault Vest blocks status moves!");
            return;
        }

        log.add(attacker.getNickname() + " used " + move.name() + "!");
        attacker.setAnalyticBoosted(attacker.getAbility() == Ability.ANALYTIC && defender.isMovedThisTurn());
        attacker.setMovedThisTurn(true);

        if (attacker.getAbility() == Ability.TRUANT) {
            if (attacker.isTruantLoafing()) {
                attacker.setTruantLoafing(false);
                log.add(attacker.getNickname() + " is loafing around!");
                return;
            }
            attacker.setTruantLoafing(true);
        }

        // Pressure: opponent's Pressure drains extra PP (cosmetic log since no PP tracking)
        // We simulate it as a flavour message only
        // (Full PP depletion system would require a larger refactor)

        // Flinch: if flinched this turn (set by opponent's move earlier), cannot act
        if (attacker.isFlinched()) {
            attacker.setFlinched(false); // clear for next turn
            log.add(attacker.getNickname() + " flinched and couldn't move!");
            // Steadfast: +1 Speed when flinched
            if (attacker.getAbility() == Ability.STEADFAST) {
                applyStage(attacker, "speed", +1, log);
                log.add(attacker.getNickname() + "'s Steadfast raised its Speed!");
            }
            return;
        }

        // Paralysis: 25% chance of full paralysis (cannot move this turn)
        if (attacker.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS
                && rng.nextInt(4) == 0) {
            log.add(attacker.getNickname() + " is fully paralyzed! It can't move!");
            return;
        }

        // Sleep: cannot move; counts down each turn, wakes when counter hits 0
        if (attacker.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.SLEEP) {
            int sleepTick = attacker.getAbility() == Ability.EARLY_BIRD ? 2 : 1;
            int remaining = attacker.getSleepCounter() - sleepTick;
            if (remaining > 0) {
                attacker.setSleepCounter(remaining);
                log.add(attacker.getNickname() + " is fast asleep!");
                return;
            } else {
                attacker.setSleepCounter(0);
                attacker.setStatusCondition(com.example.battlesimulator.model.enums.StatusCondition.NONE);
                log.add(attacker.getNickname() + " woke up!");
                // Falls through — can act this turn after waking
            }
        }

        // Freeze: cannot move; 20% chance to thaw each turn
        if (attacker.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.FREEZE) {
            if (rng.nextInt(5) != 0) {
                log.add(attacker.getNickname() + " is frozen solid!");
                return;
            }
            attacker.setStatusCondition(com.example.battlesimulator.model.enums.StatusCondition.NONE);
            log.add(attacker.getNickname() + " thawed out!");
            // Falls through — can act this turn
        }

        // ── Taunt: blocks status moves ───────────────────────────────────
        if (attacker.isTaunted()
                && move.category() == com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            log.add(attacker.getNickname() + " is taunted and can't use status moves!");
            return;
        }

        if ((attacker.getAbility() == Ability.PROTEAN || attacker.getAbility() == Ability.LIBERO)
                && move.category() != com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            attacker.setType1(resolveMoveType(attacker, move));
            attacker.setType2(Type.NONE);
            log.add(attacker.getNickname() + " changed its type with " + attacker.getAbility().getDisplayName() + "!");
        }

        SemiInvulnerableResolution semiInvulnerableResolution = resolveSemiInvulnerability(move, defender);
        if (semiInvulnerableResolution.blocked()) {
            log.add(defender.getNickname() + " avoided the attack while " + semiInvulnerableResolution.stateText() + "!");
            return;
        }

        if (move.category() == com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            if (defender.getAbility() == Ability.MAGIC_BOUNCE && attacker.getAbility() != Ability.MAGIC_BOUNCE) {
                log.add(defender.getNickname() + "'s Magic Bounce reflected the move!");
                applyStatusMove(defender, attacker, move, session, log);
                return;
            }
            applyStatusMove(attacker, defender, move, session, log);
            return;
        }

        // ── Confusion: 33% chance to hurt self instead of attacking ──────
        if (attacker.isConfused()) {
            int remaining = attacker.getConfusionCounter() - 1;
            if (remaining <= 0) {
                attacker.setConfused(false);
                attacker.setConfusionCounter(0);
                log.add(attacker.getNickname() + " snapped out of confusion!");
            } else {
                attacker.setConfusionCounter(remaining);
                log.add(attacker.getNickname() + " is confused!");
                if (rng.nextInt(3) == 0) {
                    // Hurt self: typeless physical, 40 base power, uses own Attack vs own Defense
                    int selfDmg = Math.max(1,
                            (int) Math.floor(((2.0 * attacker.getLevel() / 5.0 + 2.0) * 40
                                    * attacker.getAttack()) / (attacker.getDefense() * 50.0) + 2.0));
                    attacker.setCurrentHp(Math.max(0, attacker.getCurrentHp() - selfDmg));
                    log.add(attacker.getNickname() + " hurt itself in confusion! (−" + selfDmg + " HP)");
                    return;
                }
            }
        }

        // ── Ability-based immunities to damaging moves ────────────────────
        if (checkAbilityImmunity(attacker, defender, move, session, log)) return;

        // ── Protect / King's Shield / Spiky Shield / Baneful Bunker ──────
        if (defender.isProtecting()) {
            log.add(defender.getNickname() + " protected itself!");
            // King's Shield: contact moves lower attacker's attack by 2
            String mid = move.id().toLowerCase();
            if (mid.equals("kings-shield") && isContactMove(move))
                applyStage(attacker, "attack", -2, log);
            // Spiky Shield: contact moves deal 1/8 recoil
            if (mid.equals("spiky-shield") && isContactMove(move)) {
                int dmg = Math.max(1, attacker.getMaxHp() / 8);
                attacker.setCurrentHp(Math.max(0, attacker.getCurrentHp() - dmg));
                log.add(attacker.getNickname() + " was hurt by Spiky Shield! (−" + dmg + " HP)");
            }
            // Baneful Bunker: contact moves poison attacker
            if (mid.equals("baneful-bunker") && isContactMove(move))
                applyStatus(attacker, com.example.battlesimulator.model.enums.StatusCondition.POISON, log);
            return;
        }

        // ── Two-turn moves: charging turn ─────────────────────────────────
        String moveId = move.id().toLowerCase();
        boolean isTwoTurn = isTwoTurnMove(moveId);
        if (isTwoTurn && attacker.getChargingMove() == null) {
            // Start charging — skip the damage this turn
            attacker.setChargingMove(moveId);
            String chargeMsg = switch (moveId) {
                case "fly", "bounce" -> attacker.getNickname() + " flew up high!";
                case "dig"           -> attacker.getNickname() + " burrowed underground!";
                case "dive"          -> attacker.getNickname() + " dove underwater!";
                case "solar-beam", "solar-blade" -> attacker.getNickname() + " absorbed light!";
                case "sky-attack"    -> attacker.getNickname() + " is glowing!";
                case "skull-bash"    -> { applyStage(attacker, "defense", +1, log); yield attacker.getNickname() + " tucked in its head!"; }
                case "phantom-force", "shadow-force" -> attacker.getNickname() + " vanished instantly!";
                case "geomancy"      -> attacker.getNickname() + " is absorbing power!";
                default              -> attacker.getNickname() + " is charging up!";
            };
            log.add(chargeMsg);
            return;
        }
        // Second turn: clear charging state, proceed with full damage
        if (isTwoTurn && moveId.equals(attacker.getChargingMove())) {
            attacker.setChargingMove(null);
        }

        // ── Fixed-damage moves ─────────────────────────────────────────────
        Integer fixedDamage = getFixedDamage(attacker, defender, move);
        if (fixedDamage != null) {
            if (!accuracyService.doesMoveHit(attacker, defender, move)) {
                log.add(attacker.getNickname() + "'s attack missed!");
                return;
            }
            int dmg = Math.max(0, Math.min((int) Math.floor(fixedDamage * semiInvulnerableResolution.damageMultiplier()), defender.getCurrentHp()));
            int before = defender.getCurrentHp();
            defender.setCurrentHp(Math.max(0, before - dmg));
            if (dmg == 0) log.add("It had no effect!");
            else log.add("It dealt " + dmg + " damage. (" + defender.getNickname()
                    + " has " + defender.getCurrentHp() + "/" + defender.getMaxHp() + " HP left)");
            return;
        }

        // ── Multi-hit moves ────────────────────────────────────────────────
        int hitCount = (attacker.getAbility() == Ability.SKILL_LINK) ? getSkillLinkHitCount(move) : getMultiHitCount(move);

        if (!accuracyService.doesMoveHit(attacker, defender, move)) {
            log.add(attacker.getNickname() + "'s attack missed!");
            return;
        }

        int totalDamage = 0;
        int actualHits  = 0;
        for (int hit = 0; hit < hitCount && !defender.isFainted(); hit++) {
            int damage = damageCalculator.calculateDamage(attacker, defender, move, session.getWeather());
            damage = Math.max(0, (int) Math.floor(damage * semiInvulnerableResolution.damageMultiplier()));
            int defenderHpBefore = defender.getCurrentHp();

            // ── Substitute absorbs damage ──────────────────────────────────
            if (defender.getSubstituteHp() > 0 && attacker.getAbility() != Ability.INFILTRATOR) {
                int subRemaining = defender.getSubstituteHp() - damage;
                if (subRemaining <= 0) {
                    defender.setSubstituteHp(0);
                    log.add(defender.getNickname() + "'s substitute broke!");
                } else {
                    defender.setSubstituteHp(subRemaining);
                    log.add("The substitute took " + damage + " damage!");
                }
                actualHits++;
                totalDamage += damage;
                continue; // don't apply further item/ability effects for subbed hits
            }

            if (damage > 0 && defender.getAbility() == Ability.DISGUISE && defender.isDisguiseIntact()) {
                defender.setDisguiseIntact(false);
                log.add(defender.getNickname() + "'s Disguise absorbed the hit!");
                actualHits++;
                continue;
            }
            if (damage > 0 && defender.getAbility() == Ability.ICE_FACE
                    && defender.isIceFaceIntact()
                    && move.category() == com.example.battlesimulator.model.enums.MoveCategory.PHYSICAL) {
                defender.setIceFaceIntact(false);
                log.add(defender.getNickname() + "'s Ice Face absorbed the hit!");
                actualHits++;
                continue;
            }

            defender.setCurrentHp(Math.max(0, defender.getCurrentHp() - damage));
            actualHits++;
            totalDamage += damage;
            applyOnBeingHitEffects(attacker, defender, move, session, log);

            // ── Sturdy ────────────────────────────────────────────────────
            if (damage > 0 && defender.getCurrentHp() <= 0
                    && defender.getAbility() == Ability.STURDY
                    && defenderHpBefore == defender.getMaxHp()) {
                defender.setCurrentHp(1);
                log.add(defender.getNickname() + " endured the hit with Sturdy!");
            }
            // ── Focus Sash ────────────────────────────────────────────────
            if (damage > 0 && defender.getCurrentHp() <= 0
                    && !defender.isFocusSashUsed()
                    && defender.getHeldItem() == HeldItem.FOCUS_SASH
                    && defenderHpBefore == defender.getMaxHp()) {
                defender.setCurrentHp(1);
                defender.setFocusSashUsed(true);
                log.add(defender.getNickname() + " held on using its Focus Sash!");
            }
        }

        if (totalDamage == 0) {
            log.add("It had no effect!");
        } else if (hitCount > 1) {
            log.add("It dealt " + totalDamage + " damage over " + actualHits + " hit(s). ("+
                    defender.getNickname() + " has " + defender.getCurrentHp() + "/" + defender.getMaxHp() + " HP left)");
        } else {
            log.add("It dealt " + totalDamage + " damage. (" + defender.getNickname()
                    + " has " + defender.getCurrentHp() + "/" + defender.getMaxHp() + " HP left)");
        }

        if (totalDamage > 0) {
            // ── Life Orb ──────────────────────────────────────────────────
            if (attacker.getHeldItem() == HeldItem.LIFE_ORB) {
                int recoil = Math.max(1, attacker.getMaxHp() / 10);
                attacker.setCurrentHp(Math.max(0, attacker.getCurrentHp() - recoil));
                log.add(attacker.getNickname() + " is hurt by Life Orb! (−" + recoil + " HP)");
            }
            // ── Rocky Helmet ──────────────────────────────────────────────
            if (defender.getHeldItem() == HeldItem.ROCKY_HELMET && isContactMove(move)) {
                int recoil = Math.max(1, attacker.getMaxHp() / 6);
                attacker.setCurrentHp(Math.max(0, attacker.getCurrentHp() - recoil));
                log.add(attacker.getNickname() + " was hurt by " + defender.getNickname()
                        + "'s Rocky Helmet! (−" + recoil + " HP)");
            }
            // ── Drain moves (Leech Life, Giga Drain, etc.) ────────────────
            int drainPct = getDrainPercent(move);
            if (drainPct > 0) {
                int healed = Math.max(1, totalDamage * drainPct / 100);
                // Big Root boosts drain by 30%
                // (no Big Root in HeldItem yet — leave hook for future)
                int before = attacker.getCurrentHp();
                attacker.setCurrentHp(Math.min(attacker.getMaxHp(), before + healed));
                log.add(attacker.getNickname() + " drained HP! (+" + (attacker.getCurrentHp()-before) + " HP)");
            }
            // ── Knock Off damage bonus: 1.5× if target holds an item ──────
            // (handled in DamageCalculatorService via move id; item removal happens here)
            if (moveId.equals("knock-off") && defender.getHeldItem() != HeldItem.NONE) {
                log.add(attacker.getNickname() + " knocked off " + defender.getNickname()
                        + "'s " + defender.getHeldItem().getDisplayName() + "!");
                defender.setHeldItem(HeldItem.NONE);
            }
            // ── On-contact ability effects ─────────────────────────────────
            if (isContactMove(move)) applyOnContactAbilityEffects(attacker, defender, move, log);
            // ── Flinch ────────────────────────────────────────────────────
            if (!defender.isFainted()) applyFlinchEffect(attacker, defender, move, log);
            // ── Secondary effects ─────────────────────────────────────────
            applySecondaryEffect(attacker, defender, move, log);
            // ── U-turn / Volt Switch / Flip Turn: flag forced switch ───────
            if (moveId.equals("u-turn") || moveId.equals("volt-switch")
                    || moveId.equals("flip-turn") || moveId.equals("baton-pass")) {
                attacker.setNeedsForcedSwitch(true);
            }
            if (defender.isFainted()) {
                applyOnKnockoutAbilityEffects(attacker, log);
            }
        }
    }

    /**
     * Applies secondary stat-change effects of damaging moves.
     * e.g. Draco Meteor drops the user's Sp. Atk by 2 after hitting,
     *      Close Combat drops user's Def and Sp. Def by 1, etc.
     */
    private void applySecondaryEffect(BattlePokemon attacker, BattlePokemon defender, Move move, List<String> log) {
        switch (move.id().toLowerCase()) {
            // ── User stat drops (guaranteed on hit) ─────────────────────
            case "draco-meteor":
            case "draco meteor":
                applyStage(attacker, "specialAttack", -2, log);
                break;
            case "overheat":
            case "psycho-boost":
            case "leaf-storm":
            case "glacial-lance": // Calyrex signature — same mechanic
                applyStage(attacker, "specialAttack", -2, log);
                break;
            case "superpower":
                applyStage(attacker, "attack",  -1, log);
                applyStage(attacker, "defense", -1, log);
                break;
            case "close-combat":
                applyStage(attacker, "defense",        -1, log);
                applyStage(attacker, "specialDefense", -1, log);
                break;
            case "hammer-arm":
            case "sky-drop":
                applyStage(attacker, "speed", -1, log);
                break;
            case "v-create":
                applyStage(attacker, "defense",        -1, log);
                applyStage(attacker, "specialDefense", -1, log);
                applyStage(attacker, "speed",          -1, log);
                break;
            // ── Defender stat drops (guaranteed on hit) ──────────────────
            case "crunch":
            case "shadow-ball":
            case "earth-power":
            case "energy-ball":
            case "flash-cannon":
            case "bug-buzz":
            case "psychic":
            case "thunderbolt": // 10% chance in games, guaranteed here for simplicity
            case "discharge":
            case "bubble-beam":
            case "icy-wind":
                // These have chance-based effects in real games; skipping probability for now
                break;
            case "lunge":
                applyStage(defender, "attack", -1, log);
                break;
            case "breaking-swipe":
                applyStage(defender, "attack", -1, log);
                break;
            case "mystical-fire":
                applyStage(defender, "specialAttack", -1, log);
                break;
            case "struggle-bug":
                applyStage(defender, "specialAttack", -1, log);
                break;
            case "rock-smash":
            case "strength":
                // 50% chance in games — skipping for now
                break;
            // ── User HP drain (recoil) ────────────────────────────────────
            case "flare-blitz":
            case "brave-bird":
            case "wild-charge":
            case "head-smash":
            case "take-down":
            case "double-edge":
            case "volt-tackle": {
                int recoilDivisor = switch (move.id().toLowerCase()) {
                    case "head-smash", "flare-blitz", "brave-bird", "wild-charge", "volt-tackle" -> 3;
                    default -> 4; // take-down, double-edge
                };
                // Recoil is based on damage dealt (approximated from defender's HP change)
                int maxHpBefore = defender.getMaxHp();
                int hpAfter = defender.getCurrentHp();
                // We don't have the exact damage stored here, so re-derive from stage-adjusted attack
                // Instead, use a fraction of attacker's max HP as an approximation
                int recoil = Math.max(1, attacker.getMaxHp() / (recoilDivisor * 3));
                attacker.setCurrentHp(Math.max(0, attacker.getCurrentHp() - recoil));
                log.add(attacker.getNickname() + " is hit by recoil! (−" + recoil + " HP)");
                break;
            }
            default:
                // No secondary effect
                break;
        }
    }

    /**
     * Applies the effect of a STATUS move. Handles self-targeting stat boosts
     * (e.g. Swords Dance, Calm Mind) and opponent-targeting stat drops
     * (e.g. Growl, Leer, Screech).
     *
     * Stat stage clamped to [-6, +6] per standard Pokémon rules.
     */
    private void applyStatusMove(BattlePokemon attacker, BattlePokemon defender, Move move, BattleSession session, List<String> log) {
        String id = move.id().toLowerCase();
        switch (id) {
            // ── Self boosts ──────────────────────────────────────────────
            case "swords-dance":
                applyStage(attacker, "attack", +2, log);
                break;
            case "calm-mind":
                applyStage(attacker, "specialAttack",  +1, log);
                applyStage(attacker, "specialDefense", +1, log);
                break;
            case "nasty-plot":
            case "tail-glow":
                applyStage(attacker, "specialAttack", +2, log);
                break;
            case "dragon-dance":
                applyStage(attacker, "attack", +1, log);
                applyStage(attacker, "speed",  +1, log);
                break;
            case "bulk-up":
                applyStage(attacker, "attack",  +1, log);
                applyStage(attacker, "defense", +1, log);
                break;
            case "iron-defense":
            case "barrier":
            case "acid-armor":
                applyStage(attacker, "defense", +2, log);
                break;
            case "amnesia":
                applyStage(attacker, "specialDefense", +2, log);
                break;
            case "agility":
            case "rock-polish":
                applyStage(attacker, "speed", +2, log);
                break;
            case "quiver-dance":
                applyStage(attacker, "specialAttack",  +1, log);
                applyStage(attacker, "specialDefense", +1, log);
                applyStage(attacker, "speed",          +1, log);
                break;
            case "coil":
                applyStage(attacker, "attack",   +1, log);
                applyStage(attacker, "defense",  +1, log);
                applyStage(attacker, "accuracy", +1, log);
                break;
            case "growth":
                applyStage(attacker, "attack",        +1, log);
                applyStage(attacker, "specialAttack", +1, log);
                break;
            case "hone-claws":
                applyStage(attacker, "attack",   +1, log);
                applyStage(attacker, "accuracy", +1, log);
                break;
            case "shell-smash":
                applyStage(attacker, "defense",        -1, log);
                applyStage(attacker, "specialDefense", -1, log);
                applyStage(attacker, "attack",         +2, log);
                applyStage(attacker, "specialAttack",  +2, log);
                applyStage(attacker, "speed",          +2, log);
                break;
            case "cosmic-power":
                applyStage(attacker, "defense",        +1, log);
                applyStage(attacker, "specialDefense", +1, log);
                break;
            case "stockpile":
                applyStage(attacker, "defense",        +1, log);
                applyStage(attacker, "specialDefense", +1, log);
                break;
            case "minimize":
                applyStage(attacker, "evasion", +2, log);
                break;
            case "double-team":
                applyStage(attacker, "evasion", +1, log);
                break;
            case "recover":
            case "softboiled":
            case "roost":
            case "milk-drink":
            case "slack-off": {
                int healed = attacker.getMaxHp() / 2;
                int before = attacker.getCurrentHp();
                attacker.setCurrentHp(Math.min(attacker.getMaxHp(), before + healed));
                log.add(attacker.getNickname() + " restored " + (attacker.getCurrentHp() - before) + " HP!");
                break;
            }
            case "synthesis":
            case "moonlight":
            case "morning-sun": {
                // Heals 1/2 normally, 2/3 in Sun, 1/4 in Sand/Hail/Rain
                com.example.battlesimulator.model.enums.Weather w = session.getWeather();
                int healDivisor = switch (w) {
                    case SUN  -> 3;  // 2/3 max HP (we use maxHp*2/3)
                    case RAIN, SAND, HAIL -> 4; // 1/4
                    default -> 2; // 1/2
                };
                int healed = (w == com.example.battlesimulator.model.enums.Weather.SUN)
                        ? (attacker.getMaxHp() * 2 / 3) : (attacker.getMaxHp() / healDivisor);
                healed = Math.max(1, healed);
                int before = attacker.getCurrentHp();
                attacker.setCurrentHp(Math.min(attacker.getMaxHp(), before + healed));
                log.add(attacker.getNickname() + " restored " + (attacker.getCurrentHp() - before) + " HP!");
                break;
            }
            // ── Opponent stat drops ──────────────────────────────────────
            case "growl":
                applyStage(defender, "attack", -1, log);
                break;
            case "leer":
            case "tail-whip":
                applyStage(defender, "defense", -1, log);
                break;
            case "screech":
                applyStage(defender, "defense", -2, log);
                break;
            case "charm":
            case "feather-dance":
                applyStage(defender, "attack", -2, log);
                break;
            case "fake-tears":
            case "metal-sound":
                applyStage(defender, "specialDefense", -2, log);
                break;
            case "tickle":
                applyStage(defender, "attack",  -1, log);
                applyStage(defender, "defense", -1, log);
                break;
            case "captivate":
                applyStage(defender, "specialAttack", -2, log);
                break;
            case "sand-attack":
            case "smokescreen":
            case "mud-slap":
                applyStage(defender, "accuracy", -1, log);
                break;
            case "flash":
            case "kinesis":
                applyStage(defender, "accuracy", -1, log);
                break;
            // ── Confusion-inflicting moves ───────────────────────────────
            case "sweet-kiss":
            case "supersonic":
            case "confuse-ray":
            case "flatter":
            case "swagger":
            case "teeter-dance":
            case "dynamic-punch":
            case "hurricane": // 30% in games — treating as inflicts confusion
                applyConfusion(defender, log);
                if (id.equals("flatter")) applyStage(defender, "specialAttack", +1, log);
                if (id.equals("swagger")) applyStage(defender, "attack", +2, log);
                break;
            // ── Taunt ────────────────────────────────────────────────────
            case "taunt":
                applyTaunt(defender, log);
                break;
            case "scary-face":
                applyStage(defender, "speed", -2, log);
                break;
            case "cotton-spore":
            case "string-shot":
                applyStage(defender, "speed", -2, log);
                break;
            case "thunder-wave":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS, attacker, log);
                break;
            case "will-o-wisp":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.BURN, attacker, log);
                break;
            case "toxic":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.TOXIC, attacker, log);
                break;
            case "poison-powder":
            case "poison-gas":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.POISON, attacker, log);
                break;
            case "sleep-powder":
            case "spore":
            case "hypnosis":
            case "sing":
            case "lovely-kiss":
            case "darkvoid":
            case "grass-whistle":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.SLEEP, log);
                break;
            case "stun-spore":
            case "glare":
            case "nuzzle":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS, attacker, log);
                break;
            case "toxic-thread":
                applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.POISON, attacker, log);
                applyStage(defender, "speed", -1, log);
                break;
            // ── Weather-setting moves ────────────────────────────────────
            case "rain-dance":
                setWeather(session, com.example.battlesimulator.model.enums.Weather.RAIN, log);
                break;
            case "sunny-day":
                setWeather(session, com.example.battlesimulator.model.enums.Weather.SUN, log);
                break;
            case "sandstorm":
                setWeather(session, com.example.battlesimulator.model.enums.Weather.SAND, log);
                break;
            case "hail":
                setWeather(session, com.example.battlesimulator.model.enums.Weather.HAIL, log);
                break;
            case "snowscape":
            case "chilly-reception":
                setWeather(session, com.example.battlesimulator.model.enums.Weather.HAIL, log);
                break;
            // ── Entry Hazards ────────────────────────────────────────────
            case "stealth-rock": {
                boolean p1Atk = isPlayer1Active(attacker, session);
                // Hazard goes on the OPPONENT's side
                if (p1Atk) {
                    if (session.isStealthRockPlayer2()) {
                        log.add("Pointed stones are already floating on the opposing side!");
                    } else {
                        session.setStealthRockPlayer2(true);
                        log.add("Pointed stones float in the air around the opposing team!");
                    }
                } else {
                    if (session.isStealthRockPlayer1()) {
                        log.add("Pointed stones are already floating on the opposing side!");
                    } else {
                        session.setStealthRockPlayer1(true);
                        log.add("Pointed stones float in the air around the opposing team!");
                    }
                }
                break;
            }
            case "spikes": {
                boolean p1Atk = isPlayer1Active(attacker, session);
                if (p1Atk) {
                    int layers = session.getSpikesPlayer2();
                    if (layers >= 3) { log.add("Spikes can't be piled any higher on the opposing side!"); break; }
                    session.setSpikesPlayer2(layers + 1);
                    log.add("Spikes were scattered all around the opposing team! (Layer " + (layers + 1) + ")");
                } else {
                    int layers = session.getSpikesPlayer1();
                    if (layers >= 3) { log.add("Spikes can't be piled any higher on the opposing side!"); break; }
                    session.setSpikesPlayer1(layers + 1);
                    log.add("Spikes were scattered all around the opposing team! (Layer " + (layers + 1) + ")");
                }
                break;
            }
            case "toxic-spikes": {
                boolean p1Atk = isPlayer1Active(attacker, session);
                if (p1Atk) {
                    int layers = session.getToxicSpikesPlayer2();
                    if (layers >= 2) { log.add("Toxic Spikes can't be piled any higher on the opposing side!"); break; }
                    session.setToxicSpikesPlayer2(layers + 1);
                    log.add("Poison spikes were scattered all around the opposing team! (Layer " + (layers + 1) + ")");
                } else {
                    int layers = session.getToxicSpikesPlayer1();
                    if (layers >= 2) { log.add("Toxic Spikes can't be piled any higher on the opposing side!"); break; }
                    session.setToxicSpikesPlayer1(layers + 1);
                    log.add("Poison spikes were scattered all around the opposing team! (Layer " + (layers + 1) + ")");
                }
                break;
            }
            case "sticky-web": {
                boolean p1Atk = isPlayer1Active(attacker, session);
                if (p1Atk) {
                    if (session.isStickyWebPlayer2()) { log.add("A Sticky Web is already set on the opposing side!"); break; }
                    session.setStickyWebPlayer2(true);
                    log.add("A sticky web has been laid out beneath the opposing team's feet!");
                } else {
                    if (session.isStickyWebPlayer1()) { log.add("A Sticky Web is already set on the opposing side!"); break; }
                    session.setStickyWebPlayer1(true);
                    log.add("A sticky web has been laid out beneath the opposing team's feet!");
                }
                break;
            }
            // ── Hazard Removal ───────────────────────────────────────────
            case "rapid-spin": {
                boolean p1Atk = isPlayer1Active(attacker, session);
                boolean clearedSomething = false;
                if (p1Atk) {
                    if (session.getSpikesPlayer1() > 0)      { session.setSpikesPlayer1(0);      clearedSomething = true; }
                    if (session.getToxicSpikesPlayer1() > 0) { session.setToxicSpikesPlayer1(0); clearedSomething = true; }
                    if (session.isStealthRockPlayer1())       { session.setStealthRockPlayer1(false); clearedSomething = true; }
                    if (session.isStickyWebPlayer1())         { session.setStickyWebPlayer1(false);   clearedSomething = true; }
                } else {
                    if (session.getSpikesPlayer2() > 0)      { session.setSpikesPlayer2(0);      clearedSomething = true; }
                    if (session.getToxicSpikesPlayer2() > 0) { session.setToxicSpikesPlayer2(0); clearedSomething = true; }
                    if (session.isStealthRockPlayer2())       { session.setStealthRockPlayer2(false); clearedSomething = true; }
                    if (session.isStickyWebPlayer2())         { session.setStickyWebPlayer2(false);   clearedSomething = true; }
                }
                if (clearedSomething) log.add(attacker.getNickname() + " blew away all entry hazards on its side!");
                break;
            }
            case "defog": {
                // Defog clears hazards from BOTH sides
                boolean clearedSomething = false;
                if (session.getSpikesPlayer1() > 0)      { session.setSpikesPlayer1(0);           clearedSomething = true; }
                if (session.getSpikesPlayer2() > 0)      { session.setSpikesPlayer2(0);           clearedSomething = true; }
                if (session.getToxicSpikesPlayer1() > 0) { session.setToxicSpikesPlayer1(0);      clearedSomething = true; }
                if (session.getToxicSpikesPlayer2() > 0) { session.setToxicSpikesPlayer2(0);      clearedSomething = true; }
                if (session.isStealthRockPlayer1())       { session.setStealthRockPlayer1(false);  clearedSomething = true; }
                if (session.isStealthRockPlayer2())       { session.setStealthRockPlayer2(false);  clearedSomething = true; }
                if (session.isStickyWebPlayer1())         { session.setStickyWebPlayer1(false);    clearedSomething = true; }
                if (session.isStickyWebPlayer2())         { session.setStickyWebPlayer2(false);    clearedSomething = true; }
                applyStage(defender, "evasion", -1, log); // Defog also lowers opponent evasion
                if (clearedSomething) log.add(attacker.getNickname() + " blew away all hazards with Defog!");
                break;
            }
            // ── Belly Drum ────────────────────────────────────────────
            case "belly-drum": {
                if (attacker.getCurrentHp() <= attacker.getMaxHp() / 2) {
                    log.add(attacker.getNickname() + " doesn't have enough HP for Belly Drum!");
                } else {
                    int cost = attacker.getMaxHp() / 2;
                    attacker.setCurrentHp(attacker.getCurrentHp() - cost);
                    attacker.setAttackStage(6);
                    log.add(attacker.getNickname() + " cut its own HP and maximized its Attack!");
                }
                break;
            }
            // ── Psych Up ──────────────────────────────────────────────────
            case "psych-up": {
                attacker.setAttackStage(defender.getAttackStage());
                attacker.setDefenseStage(defender.getDefenseStage());
                attacker.setSpecialAttackStage(defender.getSpecialAttackStage());
                attacker.setSpecialDefenseStage(defender.getSpecialDefenseStage());
                attacker.setSpeedStage(defender.getSpeedStage());
                attacker.setEvasionStage(defender.getEvasionStage());
                attacker.setAccuracyStage(defender.getAccuracyStage());
                log.add(attacker.getNickname() + " copied " + defender.getNickname() + "'s stat changes!");
                break;
            }
            // ── Topsy-Turvy ───────────────────────────────────────────────
            case "topsy-turvy": {
                defender.setAttackStage(-defender.getAttackStage());
                defender.setDefenseStage(-defender.getDefenseStage());
                defender.setSpecialAttackStage(-defender.getSpecialAttackStage());
                defender.setSpecialDefenseStage(-defender.getSpecialDefenseStage());
                defender.setSpeedStage(-defender.getSpeedStage());
                defender.setEvasionStage(-defender.getEvasionStage());
                defender.setAccuracyStage(-defender.getAccuracyStage());
                log.add(defender.getNickname() + "'s stat changes were reversed!");
                break;
            }
            // ── Pain Split ────────────────────────────────────────────────
            case "pain-split": {
                int avg = (attacker.getCurrentHp() + defender.getCurrentHp()) / 2;
                attacker.setCurrentHp(Math.min(attacker.getMaxHp(), avg));
                defender.setCurrentHp(Math.min(defender.getMaxHp(), avg));
                log.add(attacker.getNickname() + " shared pain! Both HP set to ~" + avg + "!");
                break;
            }
            // ── Perish Song ───────────────────────────────────────────────
            case "perish-song": {
                boolean applied = false;
                if (attacker.getPerishCount() == 0) { attacker.setPerishCount(3); applied = true; }
                if (defender.getPerishCount() == 0) { defender.setPerishCount(3); applied = true; }
                if (applied) log.add("All Pokémon hearing the song will faint in 3 turns!");
                else log.add("The Perish Song is already playing!");
                break;
            }
            // ── Future Sight / Doom Desire ────────────────────────────────
            case "future-sight":
            case "doom-desire": {
                if (attacker.getFutureSightTurns() > 0) {
                    log.add(attacker.getNickname() + " is already focusing!"); break;
                }
                // Damage approximation: 120 base power Psychic/Steel special
                int fsBase = id.equals("future-sight") ? 120 : 140;
                int fsDmg  = Math.max(1, (int)((2.0 * attacker.getLevel() / 5.0 + 2.0)
                        * fsBase * attacker.getSpecialAttack()) / (defender.getSpecialDefense() * 50) + 2);
                attacker.setFutureSightTurns(2);
                attacker.setFutureSightDmg(fsDmg);
                String fsName = id.equals("future-sight") ? "Future Sight" : "Doom Desire";
                log.add(attacker.getNickname() + " foresaw an attack! " + fsName + " in 2 turns!");
                break;
            }
            // ── Protect / Detect / King's Shield / Spiky Shield / Baneful Bunker ──
            case "protect":
            case "detect":
            case "kings-shield":
            case "spiky-shield":
            case "baneful-bunker": {
                // Diminishing returns: each consecutive use halves success chance
                int consecutiveFails = attacker.getProtectConsecutive();
                boolean succeeds = (consecutiveFails == 0) || (rng.nextDouble() < Math.pow(0.5, consecutiveFails));
                if (succeeds) {
                    attacker.setProtecting(true);
                    attacker.setProtectConsecutive(consecutiveFails + 1);
                    log.add(attacker.getNickname() + " braced itself!");
                } else {
                    attacker.setProtectConsecutive(0);
                    log.add("But it failed!");
                }
                break;
            }
            // ── Substitute ───────────────────────────────────────────────
            case "substitute": {
                if (attacker.getSubstituteHp() > 0) {
                    log.add(attacker.getNickname() + " already has a substitute!"); break;
                }
                int subCost = attacker.getMaxHp() / 4;
                if (attacker.getCurrentHp() <= subCost) {
                    log.add(attacker.getNickname() + " is too weak to make a substitute!"); break;
                }
                attacker.setCurrentHp(attacker.getCurrentHp() - subCost);
                attacker.setSubstituteHp(subCost);
                log.add(attacker.getNickname() + " made a substitute! (Sub HP: " + subCost + ")");
                break;
            }
            // ── Baton Pass ───────────────────────────────────────────────
            case "baton-pass": {
                attacker.setBatonPassPending(true);
                attacker.setNeedsForcedSwitch(true);
                log.add(attacker.getNickname() + " is passing the baton!");
                break;
            }
            // ── Trick / Switcheroo ────────────────────────────────────────
            case "trick":
            case "switcheroo": {
                HeldItem atkItem = attacker.getHeldItem();
                HeldItem defItem = defender.getHeldItem();
                attacker.setHeldItem(defItem);
                defender.setHeldItem(atkItem);
                log.add(attacker.getNickname() + " swapped items with " + defender.getNickname() + "!");
                log.add(attacker.getNickname() + " now holds: " + defItem.getDisplayName());
                log.add(defender.getNickname() + " now holds: " + atkItem.getDisplayName());
                break;
            }
            // ── Knock Off (item removal — handled as status move for the remove effect) ──
            case "knock-off": {
                // Damage is handled in executeAttack via the damaging path.
                // When used as status move fallback just remove the item.
                if (defender.getHeldItem() != HeldItem.NONE) {
                    log.add(attacker.getNickname() + " knocked off " + defender.getNickname()
                            + "'s " + defender.getHeldItem().getDisplayName() + "!");
                    defender.setHeldItem(HeldItem.NONE);
                }
                break;
            }
            default:
                log.add("(" + move.name() + " has no implemented effect yet)");
                break;
        }
    }

    /**
     * Changes a stat stage on the given Pokémon by delta, clamped to [-6, +6].
     * Updates the actual stat multiplier and logs the result.
     */
    private void applyStage(BattlePokemon pokemon, String stat, int delta, List<String> log) {
        if (pokemon == null || delta == 0) return;
        if (pokemon.getAbility() == Ability.CONTRARY) {
            delta *= -1;
            log.add(pokemon.getNickname() + "'s Contrary reversed the stat change!");
        }
        if (pokemon.getAbility() == Ability.SIMPLE) {
            delta *= 2;
        }
        // Clear Body / White Smoke / Full Metal Body block ALL opponent-caused stat reductions
        if (delta < 0) {
            Ability ab = pokemon.getAbility();
            if (ab == Ability.CLEAR_BODY || ab == Ability.WHITE_SMOKE || ab == Ability.FULL_METAL_BODY || ab == Ability.GUARD_DOG) {
                log.add(pokemon.getNickname() + "'s " + ab.getDisplayName() + " prevents stat reductions!");
                return;
            }
            if (ab == Ability.DEFIANT) {
                applyStage(pokemon, "attack", 2, log);
            } else if (ab == Ability.COMPETITIVE) {
                applyStage(pokemon, "specialAttack", 2, log);
            }
        }
        int current;
        switch (stat) {
            case "attack"         -> current = pokemon.getAttackStage();
            case "defense"        -> current = pokemon.getDefenseStage();
            case "specialAttack"  -> current = pokemon.getSpecialAttackStage();
            case "specialDefense" -> current = pokemon.getSpecialDefenseStage();
            case "speed"          -> current = pokemon.getSpeedStage();
            case "evasion"        -> current = pokemon.getEvasionStage();
            case "accuracy"       -> current = pokemon.getAccuracyStage();
            default -> { log.add("Unknown stat: " + stat); return; }
        }

        int newStage = Math.max(-6, Math.min(6, current + delta));
        if (newStage == current) {
            String dir = delta > 0 ? "higher" : "lower";
            log.add(pokemon.getNickname() + "'s " + statDisplayName(stat) + " won't go any " + dir + "!");
            return;
        }

        switch (stat) {
            case "attack"         -> pokemon.setAttackStage(newStage);
            case "defense"        -> pokemon.setDefenseStage(newStage);
            case "specialAttack"  -> pokemon.setSpecialAttackStage(newStage);
            case "specialDefense" -> pokemon.setSpecialDefenseStage(newStage);
            case "speed"          -> pokemon.setSpeedStage(newStage);
            case "evasion"        -> pokemon.setEvasionStage(newStage);
            case "accuracy"       -> pokemon.setAccuracyStage(newStage);
        }

        int absDelta = Math.abs(delta);
        String magnitude = absDelta == 1 ? "" : absDelta == 2 ? " sharply" : " drastically";
        String direction  = delta > 0 ? " rose" + magnitude + "!" : " fell" + magnitude + "!";
        log.add(pokemon.getNickname() + "'s " + statDisplayName(stat) + direction);
    }

    /**
     * Attempts to inflict a non-volatile status condition.
     * Fails if the target already has any status condition.
     */
    private void applyStatus(BattlePokemon target,
                             com.example.battlesimulator.model.enums.StatusCondition condition,
                             List<String> log) {
        applyStatus(target, condition, null, log);
    }

    /**
     * Applies a status condition to target.
     * If target has Synchronize and the condition is burn/poison/paralysis,
     * the same condition is passed back to the source (if provided).
     */
    private void applyStatus(BattlePokemon target,
                             com.example.battlesimulator.model.enums.StatusCondition condition,
                             BattlePokemon source,
                             List<String> log) {
        if (target.getStatusCondition() != com.example.battlesimulator.model.enums.StatusCondition.NONE) {
            log.add(target.getNickname() + " is already affected by a status condition!");
            return;
        }
        // Ability type-based immunities
        if (isStatusBlockedByAbility(target, condition, log)) return;
        target.setStatusCondition(condition);
        switch (condition) {
            case BURN      -> log.add(target.getNickname() + " was burned!");
            case POISON    -> log.add(target.getNickname() + " was poisoned!");
            case TOXIC     -> { log.add(target.getNickname() + " was badly poisoned!"); target.setToxicCounter(1); }
            case PARALYSIS -> log.add(target.getNickname() + " was paralyzed! It may be unable to move!");
            case SLEEP     -> {
                int turns = 1 + rng.nextInt(3); // 1\u20133 turns
                target.setSleepCounter(turns);
                log.add(target.getNickname() + " fell asleep!");
            }
            case FREEZE    -> log.add(target.getNickname() + " was frozen solid!");
            default        -> {}
        }
        // Synchronize: reflect burn/poison/paralysis back to the source
        if (target.getAbility() == Ability.SYNCHRONIZE && source != null
                && (condition == com.example.battlesimulator.model.enums.StatusCondition.BURN
                || condition == com.example.battlesimulator.model.enums.StatusCondition.POISON
                || condition == com.example.battlesimulator.model.enums.StatusCondition.TOXIC
                || condition == com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS)) {
            log.add(target.getNickname() + "'s Synchronize passed the status to " + source.getNickname() + "!");
            applyStatus(source, condition, null, log);
        }
    }

    /**
     * Sets the battlefield weather for 5 turns, replacing any existing weather.
     */
    private void setWeather(BattleSession session,
                            com.example.battlesimulator.model.enums.Weather weather,
                            List<String> log) {
        session.setWeather(weather);
        session.setWeatherTurnsRemaining(5);
        switch (weather) {
            case RAIN -> log.add("It started to rain!");
            case SUN  -> log.add("The sunlight turned harsh!");
            case SAND -> log.add("A sandstorm kicked up!");
            case HAIL -> log.add("It started to hail!");
            default   -> {}
        }
    }

    private void setTerrain(BattleSession session, Terrain terrain, List<String> log) {
        session.setTerrain(terrain);
        session.setTerrainTurnsRemaining(5);
        switch (terrain) {
            case ELECTRIC -> log.add("Electric Terrain spread across the field!");
            case PSYCHIC -> log.add("Psychic Terrain spread across the field!");
            case GRASSY -> log.add("Grass grew to cover the battlefield!");
            case MISTY -> log.add("Mist swirled about the battlefield!");
            default -> {}
        }
    }

    /**
     * Applies end-of-turn weather damage to both active Pokémon,
     * then counts down and expires the weather if its duration has elapsed.
     */
    private void applyEndOfTurnWeather(BattleSession session,
                                       BattlePokemon p1, BattlePokemon p2,
                                       List<String> log) {
        com.example.battlesimulator.model.enums.Weather w = session.getWeather();
        if (w == com.example.battlesimulator.model.enums.Weather.NONE) return;

        // Apply damage to each active Pokémon
        for (BattlePokemon pokemon : List.of(p1, p2)) {
            if (pokemon.isFainted()) continue;
            switch (w) {
                case SAND -> {
                    // Rock, Ground, Steel are immune; Sand Rush ability also immune
                    com.example.battlesimulator.model.enums.Type t1 = pokemon.getType1();
                    com.example.battlesimulator.model.enums.Type t2 = pokemon.getType2();
                    boolean immune = isType(t1, t2,
                            com.example.battlesimulator.model.enums.Type.ROCK,
                            com.example.battlesimulator.model.enums.Type.GROUND,
                            com.example.battlesimulator.model.enums.Type.STEEL)
                            || pokemon.getAbility() == Ability.SAND_RUSH
                            || pokemon.getAbility() == Ability.SAND_FORCE
                            || pokemon.getAbility() == Ability.SAND_VEIL
                            || pokemon.getAbility() == Ability.MAGIC_GUARD;
                    if (!immune) {
                        int dmg = Math.max(1, pokemon.getMaxHp() / 16);
                        pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                        log.add(pokemon.getNickname() + " is buffeted by the sandstorm! (−" + dmg + " HP)");
                    }
                }
                case HAIL -> {
                    // Ice types are immune; Slush Rush and Magic Guard also immune
                    boolean immune = isType(pokemon.getType1(), pokemon.getType2(),
                            com.example.battlesimulator.model.enums.Type.ICE)
                            || pokemon.getAbility() == Ability.SLUSH_RUSH
                            || pokemon.getAbility() == Ability.SNOW_CLOAK
                            || pokemon.getAbility() == Ability.MAGIC_GUARD;
                    if (!immune) {
                        int dmg = Math.max(1, pokemon.getMaxHp() / 16);
                        pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                        log.add(pokemon.getNickname() + " is pelted by hail! (−" + dmg + " HP)");
                    }
                }
                default -> {} // RAIN and SUN deal no end-of-turn damage
            }
        }

        // Count down weather duration
        int remaining = session.getWeatherTurnsRemaining() - 1;
        if (remaining <= 0) {
            switch (w) {
                case RAIN -> log.add("The rain stopped.");
                case SUN  -> log.add("The harsh sunlight faded.");
                case SAND -> log.add("The sandstorm subsided.");
                case HAIL -> log.add("The hail stopped.");
                default   -> {}
            }
            session.setWeather(com.example.battlesimulator.model.enums.Weather.NONE);
            session.setWeatherTurnsRemaining(0);
        } else {
            session.setWeatherTurnsRemaining(remaining);
        }
    }

    /** Returns true if the Pokémon is any of the given types. */
    private boolean isType(com.example.battlesimulator.model.enums.Type t1,
                           com.example.battlesimulator.model.enums.Type t2,
                           com.example.battlesimulator.model.enums.Type... types) {
        for (com.example.battlesimulator.model.enums.Type t : types) {
            if (t1 == t || t2 == t) return true;
        }
        return false;
    }

    /**
     * Processes end-of-turn status damage (burn, poison, toxic).
     * Called for each active Pokémon after both moves have resolved.
     */
    private void applyEndOfTurnStatus(BattlePokemon pokemon, List<String> log) {
        // Magic Guard: immune to all indirect damage
        if (pokemon.getAbility() == Ability.MAGIC_GUARD) return;
        // Poison Heal: flips poison damage to healing (handled in applyEndOfTurnAbilityEffects)
        boolean hasPoionHeal = pokemon.getAbility() == Ability.POISON_HEAL;
        switch (pokemon.getStatusCondition()) {
            case BURN -> {
                int dmg = Math.max(1, pokemon.getMaxHp() / 8);
                // Heatproof halves burn damage
                if (pokemon.getAbility() == Ability.HEATPROOF) dmg = Math.max(1, dmg / 2);
                pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                log.add(pokemon.getNickname() + " is hurt by its burn! (−" + dmg + " HP)");
            }
            case POISON -> {
                if (hasPoionHeal) return; // handled in ability effects
                int dmg = Math.max(1, pokemon.getMaxHp() / 8);
                pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                log.add(pokemon.getNickname() + " is hurt by poison! (−" + dmg + " HP)");
            }
            case TOXIC -> {
                if (hasPoionHeal) return;
                int dmg = Math.max(1, (pokemon.getMaxHp() * pokemon.getToxicCounter()) / 16);
                pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                log.add(pokemon.getNickname() + " is hurt by bad poison! (−" + dmg + " HP)");
                pokemon.setToxicCounter(pokemon.getToxicCounter() + 1);
            }
            default -> {} // NONE, PARALYSIS, SLEEP, FREEZE — no end-of-turn HP loss
        }
    }

    private String statDisplayName(String stat) {
        return switch (stat) {
            case "attack"         -> "Attack";
            case "defense"        -> "Defense";
            case "specialAttack"  -> "Sp. Atk";
            case "specialDefense" -> "Sp. Def";
            case "speed"          -> "Speed";
            case "evasion"        -> "evasion";
            case "accuracy"       -> "accuracy";
            default               -> stat;
        };
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private void registerPendingAction(PlayerAction action) {
        pendingActions.putIfAbsent(action.battleId(), new ArrayList<>());
        List<PlayerAction> list = pendingActions.get(action.battleId());
        list.removeIf(a -> a.playerId().equals(action.playerId())); // replace if re-submitted
        list.add(action);
    }

    private boolean bothPlayersActed(String battleId) {
        List<PlayerAction> list = pendingActions.getOrDefault(battleId, List.of());
        boolean p1 = list.stream().anyMatch(a -> a.playerId().equals("player1"));
        boolean p2 = list.stream().anyMatch(a -> a.playerId().equals("player2"));
        return p1 && p2;
    }

    private void tryApplyMegaEvolution(BattlePokemon pokemon, List<String> log) {
        if (pokemon == null || pokemon.isMegaEvolved()) {
            return;
        }
        MegaEvolutionData megaData = MEGA_EVOLUTIONS.get(pokemon.getHeldItem());
        if (megaData == null) {
            return;
        }
        String baseSpecies = pokemon.getOriginalSpeciesId() != null ? pokemon.getOriginalSpeciesId() : pokemon.getSpeciesId();
        if (!megaData.baseSpecies().equalsIgnoreCase(baseSpecies)) {
            return;
        }
        pokeApiService.applyMegaEvolution(pokemon, megaData.megaSpecies(), megaData.megaAbility());
        log.add(pokemon.getNickname() + " Mega Evolved!");
    }

    private boolean isSuperEffectiveHit(Type moveType, BattlePokemon defender) {
        Type type2 = defender.getType2() != null ? defender.getType2() : Type.NONE;
        double multiplier = com.example.battlesimulator.util.TypeChart.getMultiplier(moveType, defender.getType1())
                * com.example.battlesimulator.util.TypeChart.getMultiplier(moveType, type2);
        return multiplier > 1.0;
    }

    private void maybeQueueAiAction(BattleSession session, String battleId, String actingPlayerId) {
        if (!session.isAiBattle()) {
            return;
        }
        String aiPlayerId = session.getAiPlayerId();
        if (aiPlayerId == null || aiPlayerId.equals(actingPlayerId) || session.getPhase() != Phase.CHOOSING_ACTION) {
            return;
        }
        List<PlayerAction> current = pendingActions.getOrDefault(battleId, List.of());
        boolean aiAlreadyActed = current.stream().anyMatch(action -> action.playerId().equals(aiPlayerId));
        if (aiAlreadyActed) {
            return;
        }
        PlayerAction aiAction = "competitive".equalsIgnoreCase(session.getAiMode())
                ? chooseCompetitiveSimulatedAction(battleId, aiPlayerId, session)
                : groqBattleAiService.chooseAction(battleId, aiPlayerId, session);
        registerPendingAction(aiAction);
    }

    private void maybeHandleAiForcedSwitch(BattleSession session, String battleId) {
        if (!session.isAiBattle()) {
            return;
        }
        String aiPlayerId = session.getAiPlayerId();
        if (aiPlayerId == null) {
            return;
        }
        boolean aiMustSwitch = switch (session.getPhase()) {
            case WAITING_FOR_SWITCH_P1 -> "player1".equals(aiPlayerId);
            case WAITING_FOR_SWITCH_P2 -> "player2".equals(aiPlayerId);
            case WAITING_FOR_SWITCH_BOTH -> true;
            default -> false;
        };
        if (aiMustSwitch) {
            PlayerAction switchAction = "competitive".equalsIgnoreCase(session.getAiMode())
                    ? chooseCompetitiveForcedSwitchAction(battleId, aiPlayerId, session)
                    : groqBattleAiService.chooseForcedSwitchAction(battleId, aiPlayerId, session);
            if (switchAction != null && "SWITCH".equalsIgnoreCase(switchAction.actionType())
                    && switchAction.switchIndex() != null && switchAction.switchIndex() >= 0) {
                handleAction(switchAction);
            }
        }
    }

    private PlayerAction chooseCompetitiveSimulatedAction(String battleId, String aiPlayerId, BattleSession session) {
        String opponentId = "player1".equals(aiPlayerId) ? "player2" : "player1";
        PlayerAction opponentAction = pendingActions.getOrDefault(battleId, List.of()).stream()
                .filter(action -> opponentId.equals(action.playerId()))
                .findFirst()
                .orElse(null);
        if (opponentAction == null) {
            return groqBattleAiService.chooseAction(battleId, aiPlayerId, session);
        }

        List<PlayerAction> candidates = generateCompetitiveCandidateActions(battleId, aiPlayerId, session);
        if (candidates.isEmpty()) {
            return groqBattleAiService.chooseAction(battleId, aiPlayerId, session);
        }

        return candidates.stream()
                .max(Comparator.comparingDouble(candidate ->
                        evaluateCompetitiveCandidate(session, aiPlayerId, opponentAction, candidate)))
                .orElseGet(() -> groqBattleAiService.chooseAction(battleId, aiPlayerId, session));
    }

    private PlayerAction chooseCompetitiveForcedSwitchAction(String battleId, String aiPlayerId, BattleSession session) {
        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        if (team == null) {
            return groqBattleAiService.chooseForcedSwitchAction(battleId, aiPlayerId, session);
        }

        Integer bestIndex = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < team.size(); i++) {
            BattlePokemon candidate = team.get(i);
            if (candidate == null || candidate.isFainted() || candidate == active) {
                continue;
            }
            BattleSession simulated = copyBattleSession(session, battleId + "-sim-switch");
            simulateForcedSwitch(simulated, aiPlayerId, i);
            double score = evaluateCompetitiveState(simulated, aiPlayerId);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestIndex != null) {
            return new PlayerAction(battleId, aiPlayerId, "SWITCH", null, bestIndex);
        }
        return groqBattleAiService.chooseForcedSwitchAction(battleId, aiPlayerId, session);
    }

    private List<PlayerAction> generateCompetitiveCandidateActions(String battleId, String aiPlayerId, BattleSession session) {
        List<PlayerAction> candidates = new ArrayList<>();
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        if (active == null || active.isFainted()) {
            return candidates;
        }

        Move lockedMove = getChoiceLockedMove(active);
        if (lockedMove != null) {
            candidates.add(new PlayerAction(battleId, aiPlayerId, "MOVE", lockedMove.id(), null));
        } else {
            for (Move move : active.getMoves()) {
                if (move != null) {
                    candidates.add(new PlayerAction(battleId, aiPlayerId, "MOVE", move.id(), null));
                }
            }
        }

        if (!active.isTrapped() && team != null) {
            for (int i = 0; i < team.size(); i++) {
                BattlePokemon candidate = team.get(i);
                if (candidate != null && candidate != active && !candidate.isFainted()) {
                    candidates.add(new PlayerAction(battleId, aiPlayerId, "SWITCH", null, i));
                }
            }
        }
        return candidates;
    }

    private double evaluateCompetitiveCandidate(BattleSession session, String aiPlayerId,
                                                PlayerAction opponentAction, PlayerAction aiAction) {
        BattleSession simulated = copyBattleSession(session, aiAction.battleId() + "-sim");
        List<PlayerAction> actions = List.of(copyAction(opponentAction), copyAction(aiAction));
        simulateResolvedTurn(simulated, actions);
        return evaluateCompetitiveState(simulated, aiPlayerId);
    }

    private PlayerAction copyAction(PlayerAction action) {
        return new PlayerAction(action.battleId(), action.playerId(), action.actionType(), action.targetId(), action.switchIndex());
    }

    private double evaluateCompetitiveState(BattleSession session, String aiPlayerId) {
        boolean aiIsPlayerOne = "player1".equals(aiPlayerId);
        double aiScore = evaluateSide(aiIsPlayerOne ? session.getPlayer1Team() : session.getPlayer2Team(),
                aiIsPlayerOne ? session.getPlayer1ActiveIndex() : session.getPlayer2ActiveIndex());
        double oppScore = evaluateSide(aiIsPlayerOne ? session.getPlayer2Team() : session.getPlayer1Team(),
                aiIsPlayerOne ? session.getPlayer2ActiveIndex() : session.getPlayer1ActiveIndex());
        if (session.getPhase() == Phase.BATTLE_OVER) {
            if (aiPlayerId.equals(session.getWinnerId())) {
                return 100000.0 + aiScore - oppScore;
            }
            if (session.getWinnerId() != null) {
                return -100000.0 + aiScore - oppScore;
            }
        }
        return aiScore - oppScore;
    }

    private double evaluateSide(List<BattlePokemon> team, int activeIndex) {
        if (team == null) {
            return 0.0;
        }
        double score = 0.0;
        for (int i = 0; i < team.size(); i++) {
            BattlePokemon pokemon = team.get(i);
            if (pokemon == null || pokemon.isFainted()) {
                continue;
            }
            double hpPercent = pokemon.getMaxHp() > 0 ? (pokemon.getCurrentHp() * 100.0 / pokemon.getMaxHp()) : 0.0;
            double baseValue = 150.0 + hpPercent;
            double statValue = (pokemon.getAttack() + pokemon.getDefense() + pokemon.getSpecialAttack()
                    + pokemon.getSpecialDefense() + pokemon.getSpeed()) * 0.03;
            double boostValue = (pokemon.getAttackStage() + pokemon.getDefenseStage() + pokemon.getSpecialAttackStage()
                    + pokemon.getSpecialDefenseStage() + pokemon.getSpeedStage()) * 8.0;
            double activeBonus = i == activeIndex ? 18.0 : 0.0;
            score += baseValue + statValue + boostValue + activeBonus;
        }
        return score;
    }

    private void simulateForcedSwitch(BattleSession session, String playerId, int idx) {
        List<BattlePokemon> team = "player1".equals(playerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        if (team == null || idx < 0 || idx >= team.size() || team.get(idx) == null || team.get(idx).isFainted()) {
            return;
        }
        List<String> log = new ArrayList<>();
        if ("player1".equals(playerId)) {
            BattlePokemon prev = session.getPlayer1Active();
            session.setPlayer1ActiveIndex(idx);
            BattlePokemon incoming = session.getPlayer1Active();
            if (prev != null && !prev.isFainted()) {
                applyOnSwitchOutAbility(prev, log);
            }
            tryApplyMegaEvolution(incoming, log);
            applyOnSwitchInAbility(incoming, session.getPlayer2Active(), session, log);
            applyEntryHazards(incoming, 1, session, log);
        } else {
            BattlePokemon prev = session.getPlayer2Active();
            session.setPlayer2ActiveIndex(idx);
            BattlePokemon incoming = session.getPlayer2Active();
            if (prev != null && !prev.isFainted()) {
                applyOnSwitchOutAbility(prev, log);
            }
            tryApplyMegaEvolution(incoming, log);
            applyOnSwitchInAbility(incoming, session.getPlayer1Active(), session, log);
            applyEntryHazards(incoming, 2, session, log);
        }
    }

    private BattleSession simulateResolvedTurn(BattleSession session, List<PlayerAction> actions) {
        List<String> log = new ArrayList<>();
        PlayerAction p1Action = actions.stream().filter(a -> a.playerId().equals("player1")).findFirst().orElse(null);
        PlayerAction p2Action = actions.stream().filter(a -> a.playerId().equals("player2")).findFirst().orElse(null);

        BattlePokemon p1 = session.getPlayer1Active();
        BattlePokemon p2 = session.getPlayer2Active();
        if (p1 == null || p2 == null) {
            return session;
        }
        p1.setMovedThisTurn(false);
        p2.setMovedThisTurn(false);
        p1.setAnalyticBoosted(false);
        p2.setAnalyticBoosted(false);

        if (p1Action != null && p1Action.actionType().equals("SWITCH")) {
            BattlePokemon p1Prev = session.getPlayer1Active();
            if (p1Prev != null) applyOnSwitchOutAbility(p1Prev, log);
            session.setPlayer1ActiveIndex(p1Action.switchIndex());
            p1 = session.getPlayer1Active();
            tryApplyMegaEvolution(p1, log);
            applyOnSwitchInAbility(p1, session.getPlayer2Active(), session, log);
            applyEntryHazards(p1, 1, session, log);
        }
        if (p2Action != null && p2Action.actionType().equals("SWITCH")) {
            BattlePokemon p2Prev = session.getPlayer2Active();
            if (p2Prev != null) applyOnSwitchOutAbility(p2Prev, log);
            session.setPlayer2ActiveIndex(p2Action.switchIndex());
            p2 = session.getPlayer2Active();
            tryApplyMegaEvolution(p2, log);
            applyOnSwitchInAbility(p2, session.getPlayer1Active(), session, log);
            applyEntryHazards(p2, 2, session, log);
        }

        Move p1Move = (p1Action != null && p1Action.actionType().equals("MOVE")) ? pokeApiService.getMove(p1Action.targetId()) : null;
        Move p2Move = (p2Action != null && p2Action.actionType().equals("MOVE")) ? pokeApiService.getMove(p2Action.targetId()) : null;
        int p1Priority = (p1Action != null && p1Action.actionType().equals("SWITCH")) ? 7 : adjustedPriority(p1, p1Move, session);
        int p2Priority = (p2Action != null && p2Action.actionType().equals("SWITCH")) ? 7 : adjustedPriority(p2, p2Move, session);

        double p1ParaFactor = (p1.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS) ? 0.5 : 1.0;
        double p2ParaFactor = (p2.getStatusCondition() == com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS) ? 0.5 : 1.0;
        double p1ScarfFactor = (p1.getHeldItem() == HeldItem.CHOICE_SCARF) ? 1.5 : 1.0;
        double p2ScarfFactor = (p2.getHeldItem() == HeldItem.CHOICE_SCARF) ? 1.5 : 1.0;
        double p1IronBallFactor = (p1.getHeldItem() == HeldItem.IRON_BALL) ? 0.5 : 1.0;
        double p2IronBallFactor = (p2.getHeldItem() == HeldItem.IRON_BALL) ? 0.5 : 1.0;
        double p1AbilitySpeedFactor = abilitySpeedFactor(p1, session);
        double p2AbilitySpeedFactor = abilitySpeedFactor(p2, session);
        int p1EffectiveSpeed = (int) Math.floor(p1.getSpeed() * stageMultiplier(p1.getSpeedStage()) * p1ParaFactor * p1ScarfFactor * p1IronBallFactor * p1AbilitySpeedFactor);
        int p2EffectiveSpeed = (int) Math.floor(p2.getSpeed() * stageMultiplier(p2.getSpeedStage()) * p2ParaFactor * p2ScarfFactor * p2IronBallFactor * p2AbilitySpeedFactor);
        boolean p1GoesFirst;
        if (p1Priority != p2Priority) {
            p1GoesFirst = p1Priority > p2Priority;
        } else {
            int p1SpeedForOrder = p1.getAbility() == Ability.STALL ? Integer.MIN_VALUE : p1EffectiveSpeed;
            int p2SpeedForOrder = p2.getAbility() == Ability.STALL ? Integer.MIN_VALUE : p2EffectiveSpeed;
            p1GoesFirst = p1SpeedForOrder != p2SpeedForOrder ? p1SpeedForOrder > p2SpeedForOrder : true;
        }

        if (p1GoesFirst) {
            if (p1Move != null) executeAttack(p1, p2, p1Move, session, log);
            if (!p2.isFainted() && p2Move != null) executeAttack(p2, p1, p2Move, session, log);
        } else {
            if (p2Move != null) executeAttack(p2, p1, p2Move, session, log);
            if (!p1.isFainted() && p1Move != null) executeAttack(p1, p2, p1Move, session, log);
        }

        if (p1.isProtecting()) { p1.setProtecting(false); } else { p1.setProtectConsecutive(0); }
        if (p2.isProtecting()) { p2.setProtecting(false); } else { p2.setProtectConsecutive(0); }

        if (!p1.isFainted()) applyEndOfTurnStatus(p1, log);
        if (!p2.isFainted()) applyEndOfTurnStatus(p2, log);
        if (!p1.isFainted()) applyEndOfTurnAbilityEffects(p1, session, log);
        if (!p2.isFainted()) applyEndOfTurnAbilityEffects(p2, session, log);
        if (!p1.isFainted()) applyEndOfTurnItemEffects(p1, log);
        if (!p2.isFainted()) applyEndOfTurnItemEffects(p2, log);
        if (!p1.isFainted()) tickVolatileConditions(p1, log);
        if (!p2.isFainted()) tickVolatileConditions(p2, log);
        applyEndOfTurnWeather(session, p1, p2, log);

        boolean p1Fainted = p1.isFainted();
        boolean p2Fainted = p2.isFainted();
        if (p1Fainted && hasTrappingAbility(p1)) p2.setTrapped(false);
        if (p2Fainted && hasTrappingAbility(p2)) p1.setTrapped(false);

        if (session.isPlayer1AllFainted()) {
            session.setPhase(Phase.BATTLE_OVER);
            session.setWinnerId("player2");
        } else if (session.isPlayer2AllFainted()) {
            session.setPhase(Phase.BATTLE_OVER);
            session.setWinnerId("player1");
        } else if (p1Fainted && p2Fainted) {
            session.setPhase(Phase.WAITING_FOR_SWITCH_BOTH);
        } else if (p1Fainted) {
            session.setPhase(Phase.WAITING_FOR_SWITCH_P1);
        } else if (p2Fainted) {
            session.setPhase(Phase.WAITING_FOR_SWITCH_P2);
        } else {
            session.setPhase(Phase.CHOOSING_ACTION);
            session.setTurnCount(session.getTurnCount() + 1);
        }

        return session;
    }

    private Move getChoiceLockedMove(BattlePokemon active) {
        if (active == null || active.getChoiceLock() == null || active.getHeldItem() == null) {
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

    private BattleSession copyBattleSession(BattleSession original, String battleId) {
        BattleSession copy = new BattleSession(battleId);
        copy.setPlayer1Team(copyTeam(original.getPlayer1Team()));
        copy.setPlayer2Team(copyTeam(original.getPlayer2Team()));
        copy.setPlayer1ActiveIndex(original.getPlayer1ActiveIndex());
        copy.setPlayer2ActiveIndex(original.getPlayer2ActiveIndex());
        copy.setTurnCount(original.getTurnCount());
        copy.setPhase(original.getPhase());
        copy.setWinnerId(original.getWinnerId());
        copy.setAiBattle(original.isAiBattle());
        copy.setAiPlayerId(original.getAiPlayerId());
        copy.setAiMode(original.getAiMode());
        copy.setBattleHistory(new ArrayList<>(original.getBattleHistory()));
        copy.setWeather(original.getWeather());
        copy.setWeatherTurnsRemaining(original.getWeatherTurnsRemaining());
        copy.setTerrain(original.getTerrain());
        copy.setTerrainTurnsRemaining(original.getTerrainTurnsRemaining());
        copy.setSpikesPlayer1(original.getSpikesPlayer1());
        copy.setSpikesPlayer2(original.getSpikesPlayer2());
        copy.setToxicSpikesPlayer1(original.getToxicSpikesPlayer1());
        copy.setToxicSpikesPlayer2(original.getToxicSpikesPlayer2());
        copy.setStealthRockPlayer1(original.isStealthRockPlayer1());
        copy.setStealthRockPlayer2(original.isStealthRockPlayer2());
        copy.setStickyWebPlayer1(original.isStickyWebPlayer1());
        copy.setStickyWebPlayer2(original.isStickyWebPlayer2());
        return copy;
    }

    private List<BattlePokemon> copyTeam(List<BattlePokemon> team) {
        List<BattlePokemon> copied = new ArrayList<>();
        for (BattlePokemon pokemon : team) {
            copied.add(pokemon == null ? null : copyPokemon(pokemon));
        }
        return copied;
    }

    private BattlePokemon copyPokemon(BattlePokemon pokemon) {
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
                .confused(pokemon.isConfused())
                .confusionCounter(pokemon.getConfusionCounter())
                .taunted(pokemon.isTaunted())
                .tauntCounter(pokemon.getTauntCounter())
                .flinched(pokemon.isFlinched())
                .trapped(pokemon.isTrapped())
                .critStageBonus(pokemon.getCritStageBonus())
                .pressurePpDrain(pokemon.getPressurePpDrain())
                .chargingMove(pokemon.getChargingMove())
                .protecting(pokemon.isProtecting())
                .protectConsecutive(pokemon.getProtectConsecutive())
                .substituteHp(pokemon.getSubstituteHp())
                .perishCount(pokemon.getPerishCount())
                .futureSightTurns(pokemon.getFutureSightTurns())
                .futureSightDmg(pokemon.getFutureSightDmg())
                .batonPassPending(pokemon.isBatonPassPending())
                .needsForcedSwitch(pokemon.isNeedsForcedSwitch())
                .moves(pokemon.getMoves() == null ? List.of() : new ArrayList<>(pokemon.getMoves()))
                .ability(pokemon.getAbility())
                .flashFireActive(pokemon.isFlashFireActive())
                .unburdened(pokemon.isUnburdened())
                .disguiseIntact(pokemon.isDisguiseIntact())
                .iceFaceIntact(pokemon.isIceFaceIntact())
                .truantLoafing(pokemon.isTruantLoafing())
                .slowStartTurns(pokemon.getSlowStartTurns())
                .supremeOverlordStacks(pokemon.getSupremeOverlordStacks())
                .movedThisTurn(pokemon.isMovedThisTurn())
                .analyticBoosted(pokemon.isAnalyticBoosted())
                .heldItem(pokemon.getHeldItem())
                .focusSashUsed(pokemon.isFocusSashUsed())
                .berryUsed(pokemon.isBerryUsed())
                .weaknessPolicyUsed(pokemon.isWeaknessPolicyUsed())
                .airBalloonPopped(pokemon.isAirBalloonPopped())
                .megaEvolved(pokemon.isMegaEvolved())
                .choiceLock(pokemon.getChoiceLock())
                .build();
    }

    private TurnResult buildSnapshot(BattleSession session, List<String> log, boolean battleOver) {
        if (log != null && !log.isEmpty()) {
            session.getBattleHistory().addAll(log);
        }
        List<PokemonSnapshot> t1 = session.getPlayer1Team().stream()
                .map(p -> p == null ? null : PokemonSnapshot.of(p)).toList();
        List<PokemonSnapshot> t2 = session.getPlayer2Team().stream()
                .map(p -> p == null ? null : PokemonSnapshot.of(p)).toList();
        return new TurnResult(
                session.getBattleId(), log, t1, t2,
                session.getPlayer1ActiveIndex(), session.getPlayer2ActiveIndex(),
                session.getPhase().name(), session.getWinnerId(),
                session.getWeather().name(),
                session.getSpikesPlayer1(), session.getSpikesPlayer2(),
                session.getToxicSpikesPlayer1(), session.getToxicSpikesPlayer2(),
                session.isStealthRockPlayer1(), session.isStealthRockPlayer2(),
                session.isStickyWebPlayer1(), session.isStickyWebPlayer2());
    }

    private void broadcast(String battleId, TurnResult result) {
        messagingTemplate.convertAndSend("/topic/battle/" + battleId, result);
    }

    /** Standard Pokémon stat-stage multiplier: (2+stage)/2 for positive, 2/(2-stage) for negative. */
    private double stageMultiplier(int stage) {
        if (stage >= 0) return (2.0 + stage) / 2.0;
        else            return 2.0 / (2.0 - stage);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private int adjustedPriority(BattlePokemon pokemon, Move move, BattleSession session) {
        if (pokemon == null || move == null) return 0;
        int priority = move.priority();
        if (pokemon.getAbility() == Ability.PRANKSTER && move.category() == com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            priority++;
        }
        if (pokemon.getAbility() == Ability.GALE_WINGS && move.type() == Type.FLYING && pokemon.getCurrentHp() == pokemon.getMaxHp()) {
            priority++;
        }
        if (pokemon.getAbility() == Ability.TRIAGE && isHealingMove(move)) {
            priority += 3;
        }
        if (session != null && blocksPriority(session, pokemon, priority)) {
            return Integer.MIN_VALUE / 2;
        }
        return priority;
    }

    private boolean isHealingMove(Move move) {
        return java.util.Set.of(
                "recover", "roost", "soft-boiled", "slack-off", "synthesis", "moonlight",
                "milk-drink", "heal-order", "shore-up", "drain-punch", "giga-drain",
                "draining-kiss", "horn-leech", "leech-life", "oblivion-wing"
        ).contains(move.id().toLowerCase());
    }

    private Type resolveMoveType(BattlePokemon attacker, Move move) {
        Ability ability = attacker.getAbility();
        if (ability == null || move.category() == com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            return move.type();
        }
        if (ability == Ability.NORMALIZE) return Type.NORMAL;
        if (ability == Ability.LIQUID_VOICE && isSoundMove(move)) return Type.WATER;
        if (move.type() != Type.NORMAL) return move.type();
        return switch (ability) {
            case AERILATE -> Type.FLYING;
            case REFRIGERATE -> Type.ICE;
            case PIXILATE -> Type.FAIRY;
            case GALVANIZE -> Type.ELECTRIC;
            default -> move.type();
        };
    }

    private boolean blocksPriority(BattleSession session, BattlePokemon attacker, int priority) {
        if (priority <= 0) return false;
        BattlePokemon defender = session.getPlayer1Active() == attacker ? session.getPlayer2Active() : session.getPlayer1Active();
        if (defender == null) return false;
        Ability ability = defender.getAbility();
        return ability == Ability.DAZZLING || ability == Ability.QUEENLY_MAJESTY || ability == Ability.ARMOR_TAIL;
    }

    private int countFaintedAllies(BattlePokemon pokemon, BattleSession session) {
        List<BattlePokemon> team = isPlayer1Active(pokemon, session) ? session.getPlayer1Team() : session.getPlayer2Team();
        int count = 0;
        for (BattlePokemon member : team) {
            if (member != null && member != pokemon && member.isFainted()) count++;
        }
        return count;
    }

    private void copyStages(BattlePokemon source, BattlePokemon target) {
        target.setAttackStage(source.getAttackStage());
        target.setDefenseStage(source.getDefenseStage());
        target.setSpecialAttackStage(source.getSpecialAttackStage());
        target.setSpecialDefenseStage(source.getSpecialDefenseStage());
        target.setSpeedStage(source.getSpeedStage());
        target.setEvasionStage(source.getEvasionStage());
        target.setAccuracyStage(source.getAccuracyStage());
    }

    /** Returns a speed multiplier granted by the Pokémon's ability given the current weather. */
    private double abilitySpeedFactor(BattlePokemon pokemon, BattleSession session) {
        if (pokemon.getAbility() == null) return 1.0;
        com.example.battlesimulator.model.enums.Weather w = session.getWeather();
        return switch (pokemon.getAbility()) {
            case SWIFT_SWIM  -> (w == com.example.battlesimulator.model.enums.Weather.RAIN) ? 2.0 : 1.0;
            case CHLOROPHYLL -> (w == com.example.battlesimulator.model.enums.Weather.SUN)  ? 2.0 : 1.0;
            case SAND_RUSH   -> (w == com.example.battlesimulator.model.enums.Weather.SAND) ? 2.0 : 1.0;
            case SLUSH_RUSH  -> (w == com.example.battlesimulator.model.enums.Weather.HAIL) ? 2.0 : 1.0;
            case QUICK_FEET  -> (pokemon.getStatusCondition() != com.example.battlesimulator.model.enums.StatusCondition.NONE) ? 1.5 : 1.0;
            case UNBURDEN    -> pokemon.isUnburdened() ? 2.0 : 1.0;
            case SLOW_START  -> pokemon.getSlowStartTurns() > 0 ? 0.5 : 1.0;
            default -> 1.0;
        };
    }

    // -------------------------------------------------------------------------
    // ABILITY EFFECTS
    // -------------------------------------------------------------------------

    /**
     * Fired when a Pokémon enters the field (battle start or switch-in).
     * Handles: Intimidate, weather-setters, Flash Fire init, etc.
     */
    private void applyOnSwitchInAbility(BattlePokemon incoming, BattlePokemon opponent,
                                        BattleSession session, List<String> log) {
        if (incoming == null || incoming.getAbility() == null) return;
        incoming.setType1(incoming.getBaseType1());
        incoming.setType2(incoming.getBaseType2());
        incoming.setSlowStartTurns(5);
        incoming.setSupremeOverlordStacks(countFaintedAllies(incoming, session));
        switch (incoming.getAbility()) {
            case INTIMIDATE -> {
                if (opponent != null && !opponent.isFainted()) {
                    // Clear Body / White Smoke / Full Metal Body block Intimidate
                    if (opponent.getAbility() == Ability.CLEAR_BODY
                            || opponent.getAbility() == Ability.WHITE_SMOKE
                            || opponent.getAbility() == Ability.FULL_METAL_BODY
                            || opponent.getAbility() == Ability.GUARD_DOG) {
                        log.add(incoming.getNickname() + "'s Intimidate was blocked by "
                                + opponent.getNickname() + "'s " + opponent.getAbility().getDisplayName() + "!");
                    } else {
                        applyStage(opponent, "attack", -1, log);
                        log.add(incoming.getNickname() + "'s Intimidate lowered "
                                + opponent.getNickname() + "'s Attack!");
                    }
                }
            }
            case DRIZZLE  -> setWeather(session, com.example.battlesimulator.model.enums.Weather.RAIN, log);
            case DROUGHT  -> setWeather(session, com.example.battlesimulator.model.enums.Weather.SUN,  log);
            case SAND_STREAM -> setWeather(session, com.example.battlesimulator.model.enums.Weather.SAND, log);
            case SNOW_WARNING -> setWeather(session, com.example.battlesimulator.model.enums.Weather.HAIL, log);
            case ELECTRIC_SURGE -> setTerrain(session, Terrain.ELECTRIC, log);
            case PSYCHIC_SURGE -> setTerrain(session, Terrain.PSYCHIC, log);
            case GRASSY_SURGE -> setTerrain(session, Terrain.GRASSY, log);
            case MISTY_SURGE -> setTerrain(session, Terrain.MISTY, log);
            case INTREPID_SWORD -> applyStage(incoming, "attack", 1, log);
            case DAUNTLESS_SHIELD -> applyStage(incoming, "defense", 1, log);
            case DOWNLOAD -> {
                if (opponent != null && !opponent.isFainted()) {
                    int physicalBulk = opponent.getDefense();
                    int specialBulk = opponent.getSpecialDefense();
                    if (specialBulk <= physicalBulk) applyStage(incoming, "specialAttack", 1, log);
                    else applyStage(incoming, "attack", 1, log);
                }
            }
            case SAND_VEIL -> {
                if (session.getWeather() == com.example.battlesimulator.model.enums.Weather.SAND
                        && incoming.getEvasionStage() < 6) {
                    incoming.setEvasionStage(Math.min(6, incoming.getEvasionStage() + 1));
                }
            }
            case SNOW_CLOAK -> {
                if (session.getWeather() == com.example.battlesimulator.model.enums.Weather.HAIL
                        && incoming.getEvasionStage() < 6) {
                    incoming.setEvasionStage(Math.min(6, incoming.getEvasionStage() + 1));
                }
            }
            case SUPER_LUCK -> incoming.setCritStageBonus(1);
            case PRESSURE -> log.add(incoming.getNickname() + " is exerting its Pressure!");
            case ARENA_TRAP -> {
                if (opponent != null && !opponent.isFainted()
                        && opponent.getAbility() != Ability.LEVITATE
                        && opponent.getType1() != com.example.battlesimulator.model.enums.Type.FLYING
                        && opponent.getType2() != com.example.battlesimulator.model.enums.Type.FLYING) {
                    opponent.setTrapped(true);
                    log.add(incoming.getNickname() + "'s Arena Trap prevents " + opponent.getNickname() + " from fleeing!");
                }
            }
            case SHADOW_TAG -> {
                if (opponent != null && !opponent.isFainted()
                        && opponent.getAbility() != Ability.SHADOW_TAG) {
                    opponent.setTrapped(true);
                    log.add(incoming.getNickname() + "'s Shadow Tag prevents " + opponent.getNickname() + " from switching!");
                }
            }
            case MAGNET_PULL -> {
                if (opponent != null && !opponent.isFainted()
                        && (opponent.getType1() == com.example.battlesimulator.model.enums.Type.STEEL
                        || opponent.getType2() == com.example.battlesimulator.model.enums.Type.STEEL)) {
                    opponent.setTrapped(true);
                    log.add(incoming.getNickname() + "'s Magnet Pull prevents " + opponent.getNickname() + " from switching!");
                }
            }
            case TRACE -> {
                if (opponent != null && opponent.getAbility() != null
                        && opponent.getAbility() != Ability.NONE
                        && opponent.getAbility() != Ability.TRACE) {
                    incoming.setAbility(opponent.getAbility());
                    log.add(incoming.getNickname() + " traced " + opponent.getNickname()
                            + "'s " + opponent.getAbility().getDisplayName() + "!");
                    // Re-apply the traced ability's switch-in effect
                    applyOnSwitchInAbility(incoming, opponent, session, log);
                }
            }
            case COSTAR -> {
                BattlePokemon ally = session.getPlayer1Active() == incoming ? session.getPlayer2Active() : session.getPlayer1Active();
                if (ally != null && ally != opponent) {
                    copyStages(ally, incoming);
                    log.add(incoming.getNickname() + " copied stat changes with Costar!");
                }
            }
            default -> {}
        }
    }

    /**
     * Fired when a Pokémon is withdrawn (switches out voluntarily, not fainted).
     * Handles: Natural Cure, Regenerator.
     */
    private void applyOnSwitchOutAbility(BattlePokemon withdrawing, List<String> log) {
        // Volatile conditions always clear on switch-out
        if (withdrawing.isConfused()) {
            withdrawing.setConfused(false);
            withdrawing.setConfusionCounter(0);
        }
        if (withdrawing.isTaunted()) {
            withdrawing.setTaunted(false);
            withdrawing.setTauntCounter(0);
        }
        withdrawing.setTrapped(false);
        withdrawing.setCritStageBonus(0);
        withdrawing.setFlinched(false);
        withdrawing.setProtecting(false);
        withdrawing.setProtectConsecutive(0);
        withdrawing.setSubstituteHp(0);
        withdrawing.setChargingMove(null);
        withdrawing.setNeedsForcedSwitch(false);
        withdrawing.setBatonPassPending(false);
        withdrawing.setChoiceLock(null);
        withdrawing.setType1(withdrawing.getBaseType1());
        withdrawing.setType2(withdrawing.getBaseType2());
        withdrawing.setTruantLoafing(false);
        // Note: perishCount and futureSightTurns intentionally persist through switch
        if (withdrawing == null || withdrawing.getAbility() == null) return;
        switch (withdrawing.getAbility()) {
            case NATURAL_CURE -> {
                if (withdrawing.getStatusCondition() != com.example.battlesimulator.model.enums.StatusCondition.NONE) {
                    withdrawing.setStatusCondition(com.example.battlesimulator.model.enums.StatusCondition.NONE);
                    withdrawing.setSleepCounter(0);
                    withdrawing.setToxicCounter(0);
                    log.add(withdrawing.getNickname() + "'s status was cured by Natural Cure!");
                }
            }
            case REGENERATOR -> {
                int healed = withdrawing.getMaxHp() / 3;
                int before = withdrawing.getCurrentHp();
                withdrawing.setCurrentHp(Math.min(withdrawing.getMaxHp(), before + healed));
                int actual = withdrawing.getCurrentHp() - before;
                if (actual > 0) {
                    log.add(withdrawing.getNickname() + " restored HP with Regenerator! (+" + actual + " HP)");
                }
            }
            default -> {}
        }
    }

    /**
     * Checks if the defender's ability makes it immune to the incoming move.
     * Also handles ability-activation side-effects (Flash Fire boost, stat boosts, HP restore).
     * Returns true if the move is blocked entirely.
     */
    private boolean checkAbilityImmunity(BattlePokemon attacker, BattlePokemon defender,
                                         Move move, BattleSession session, List<String> log) {
        Ability ability = defender.getAbility();
        if (ability == null || ability == Ability.NONE) return false;

        com.example.battlesimulator.model.enums.Type moveType = resolveMoveType(attacker, move);

        if (ability == Ability.GOOD_AS_GOLD && move.category() == com.example.battlesimulator.model.enums.MoveCategory.STATUS) {
            log.add(defender.getNickname() + "'s Good as Gold blocked the status move!");
            return true;
        }
        if (defender.getHeldItem() == HeldItem.AIR_BALLOON
                && !defender.isAirBalloonPopped()
                && moveType == com.example.battlesimulator.model.enums.Type.GROUND) {
            log.add(defender.getNickname() + " is floating on an Air Balloon!");
            return true;
        }
        if (ability == Ability.BULLETPROOF && isBallOrBombMove(move)) {
            log.add(defender.getNickname() + "'s Bulletproof blocked the move!");
            return true;
        }
        if (ability == Ability.WIND_RIDER && isWindMove(move)) {
            applyStage(defender, "attack", 1, log);
            log.add(defender.getNickname() + " rode the wind and gained Attack!");
            return true;
        }

        switch (ability) {
            // ── Type immunities ────────────────────────────────────────────
            case LEVITATE -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.GROUND) {
                    log.add(defender.getNickname() + " is unaffected due to Levitate!");
                    return true;
                }
            }
            case WATER_ABSORB -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.WATER) {
                    int heal = Math.max(1, defender.getMaxHp() / 4);
                    defender.setCurrentHp(Math.min(defender.getMaxHp(), defender.getCurrentHp() + heal));
                    log.add(defender.getNickname() + " absorbed the Water move with Water Absorb! (+" + heal + " HP)");
                    return true;
                }
            }
            case DRY_SKIN -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.WATER) {
                    int heal = Math.max(1, defender.getMaxHp() / 4);
                    defender.setCurrentHp(Math.min(defender.getMaxHp(), defender.getCurrentHp() + heal));
                    log.add(defender.getNickname() + " absorbed the Water move with Dry Skin! (+" + heal + " HP)");
                    return true;
                }
            }
            case VOLT_ABSORB -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.ELECTRIC) {
                    int heal = Math.max(1, defender.getMaxHp() / 4);
                    defender.setCurrentHp(Math.min(defender.getMaxHp(), defender.getCurrentHp() + heal));
                    log.add(defender.getNickname() + " absorbed the Electric move with Volt Absorb! (+" + heal + " HP)");
                    return true;
                }
            }
            case EARTH_EATER -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.GROUND) {
                    int heal = Math.max(1, defender.getMaxHp() / 4);
                    defender.setCurrentHp(Math.min(defender.getMaxHp(), defender.getCurrentHp() + heal));
                    log.add(defender.getNickname() + "'s Earth Eater restored HP! (+" + heal + " HP)");
                    return true;
                }
            }
            case WELL_BAKED_BODY -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.FIRE) {
                    applyStage(defender, "defense", 2, log);
                    log.add(defender.getNickname() + "'s Well-Baked Body boosted its Defense!");
                    return true;
                }
            }
            case FLASH_FIRE -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.FIRE) {
                    defender.setFlashFireActive(true);
                    log.add(defender.getNickname() + "'s Flash Fire was activated! Fire moves are now stronger!");
                    return true;
                }
            }
            case LIGHTNING_ROD -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.ELECTRIC) {
                    applyStage(defender, "specialAttack", +1, log);
                    log.add(defender.getNickname() + "'s Lightning Rod drew the Electric move and boosted Sp. Atk!");
                    return true;
                }
            }
            case STORM_DRAIN -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.WATER) {
                    applyStage(defender, "specialAttack", +1, log);
                    log.add(defender.getNickname() + "'s Storm Drain drew the Water move and boosted Sp. Atk!");
                    return true;
                }
            }
            case SAP_SIPPER -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.GRASS) {
                    applyStage(defender, "attack", +1, log);
                    log.add(defender.getNickname() + "'s Sap Sipper absorbed the Grass move and boosted Attack!");
                    return true;
                }
            }
            case MOTOR_DRIVE -> {
                if (moveType == com.example.battlesimulator.model.enums.Type.ELECTRIC) {
                    applyStage(defender, "speed", +1, log);
                    log.add(defender.getNickname() + "'s Motor Drive absorbed the Electric move and boosted Speed!");
                    return true;
                }
            }
            case WONDER_GUARD -> {
                // Only super-effective moves get through
                double effectiveness = com.example.battlesimulator.util.TypeChart.getMultiplier(moveType, defender.getType1())
                        * com.example.battlesimulator.util.TypeChart.getMultiplier(moveType, defender.getType2());
                if (effectiveness <= 1.0) {
                    log.add(defender.getNickname() + "'s Wonder Guard blocked the move!");
                    return true;
                }
            }
            case SOUNDPROOF -> {
                if (isSoundMove(move)) {
                    log.add(defender.getNickname() + "'s Soundproof blocked the sound-based move!");
                    return true;
                }
            }
            default -> {}
        }
        return false;
    }

    /**
     * Checks if a status condition can be applied given the target's ability.
     * Returns true if the ability BLOCKS the status (and logs it).
     */
    private boolean isStatusBlockedByAbility(BattlePokemon target,
                                             com.example.battlesimulator.model.enums.StatusCondition condition,
                                             List<String> log) {
        Ability ability = target.getAbility();
        if (ability == null) return false;
        return switch (condition) {
            case POISON, TOXIC -> {
                if (ability == Ability.IMMUNITY || ability == Ability.PASTEL_VEIL || ability == Ability.PURIFYING_SALT
                        || target.getType1() == com.example.battlesimulator.model.enums.Type.POISON
                        || target.getType2() == com.example.battlesimulator.model.enums.Type.POISON
                        || target.getType1() == com.example.battlesimulator.model.enums.Type.STEEL
                        || target.getType2() == com.example.battlesimulator.model.enums.Type.STEEL) {
                    log.add(target.getNickname() + " cannot be poisoned!");
                    yield true;
                }
                // Poison Heal holder won't get a different status, but we handle it separately
                yield false;
            }
            case BURN -> {
                if (ability == Ability.WATER_VEIL || ability == Ability.HEATPROOF || ability == Ability.WATER_BUBBLE
                        || target.getType1() == com.example.battlesimulator.model.enums.Type.FIRE
                        || target.getType2() == com.example.battlesimulator.model.enums.Type.FIRE) {
                    log.add(target.getNickname() + " cannot be burned!");
                    yield true;
                }
                yield false;
            }
            case FREEZE -> {
                if (ability == Ability.MAGMA_ARMOR
                        || target.getType1() == com.example.battlesimulator.model.enums.Type.ICE
                        || target.getType2() == com.example.battlesimulator.model.enums.Type.ICE) {
                    log.add(target.getNickname() + " cannot be frozen!");
                    yield true;
                }
                yield false;
            }
            case PARALYSIS -> {
                if (ability == Ability.LIMBER || ability == Ability.COMATOSE
                        || target.getType1() == com.example.battlesimulator.model.enums.Type.ELECTRIC
                        || target.getType2() == com.example.battlesimulator.model.enums.Type.ELECTRIC) {
                    log.add(target.getNickname() + " cannot be paralyzed!");
                    yield true;
                }
                yield false;
            }
            case SLEEP -> {
                if (ability == Ability.INSOMNIA || ability == Ability.VITAL_SPIRIT || ability == Ability.COMATOSE) {
                    log.add(target.getNickname() + " cannot fall asleep!");
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    /**
     * Applies end-of-turn ability effects: Speed Boost, Solar Power, Poison Heal, etc.
     */
    private void applyEndOfTurnAbilityEffects(BattlePokemon pokemon, BattleSession session, List<String> log) {
        if (pokemon == null || pokemon.isFainted() || pokemon.getAbility() == null) return;
        if (pokemon.getAbility() == Ability.SLOW_START && pokemon.getSlowStartTurns() > 0) {
            pokemon.setSlowStartTurns(pokemon.getSlowStartTurns() - 1);
            if (pokemon.getSlowStartTurns() == 0) {
                log.add(pokemon.getNickname() + "'s Slow Start wore off!");
            }
        }
        switch (pokemon.getAbility()) {
            case SPEED_BOOST -> applyStage(pokemon, "speed", +1, log);
            case SOLAR_POWER -> {
                if (session.getWeather() == com.example.battlesimulator.model.enums.Weather.SUN) {
                    int dmg = Math.max(1, pokemon.getMaxHp() / 8);
                    pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                    log.add(pokemon.getNickname() + " is hurt by Solar Power! (−" + dmg + " HP)");
                }
            }
            case POISON_HEAL -> {
                com.example.battlesimulator.model.enums.StatusCondition sc = pokemon.getStatusCondition();
                if (sc == com.example.battlesimulator.model.enums.StatusCondition.POISON
                        || sc == com.example.battlesimulator.model.enums.StatusCondition.TOXIC) {
                    int heal = Math.max(1, pokemon.getMaxHp() / 8);
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                    log.add(pokemon.getNickname() + " restored HP with Poison Heal! (+" + heal + " HP)");
                }
            }
            case RAIN_DISH -> {
                if (session.getWeather() == Weather.RAIN) {
                    int heal = Math.max(1, pokemon.getMaxHp() / 16);
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                    log.add(pokemon.getNickname() + " restored HP with Rain Dish! (+" + heal + " HP)");
                }
            }
            case ICE_BODY -> {
                if (session.getWeather() == Weather.HAIL) {
                    int heal = Math.max(1, pokemon.getMaxHp() / 16);
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                    log.add(pokemon.getNickname() + " restored HP with Ice Body! (+" + heal + " HP)");
                }
            }
            case DRY_SKIN -> {
                if (session.getWeather() == Weather.RAIN) {
                    int heal = Math.max(1, pokemon.getMaxHp() / 8);
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                    log.add(pokemon.getNickname() + " restored HP with Dry Skin! (+" + heal + " HP)");
                } else if (session.getWeather() == Weather.SUN) {
                    int dmg = Math.max(1, pokemon.getMaxHp() / 8);
                    pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                    log.add(pokemon.getNickname() + " is hurt by Dry Skin in the sun! (-" + dmg + " HP)");
                }
            }
            case HYDRATION -> {
                if (session.getWeather() == Weather.RAIN && pokemon.getStatusCondition() != com.example.battlesimulator.model.enums.StatusCondition.NONE) {
                    pokemon.setStatusCondition(com.example.battlesimulator.model.enums.StatusCondition.NONE);
                    pokemon.setSleepCounter(0);
                    pokemon.setToxicCounter(0);
                    log.add(pokemon.getNickname() + "'s Hydration cured its status!");
                }
            }
            case SHED_SKIN -> {
                if (pokemon.getStatusCondition() != com.example.battlesimulator.model.enums.StatusCondition.NONE && rng.nextInt(3) == 0) {
                    pokemon.setStatusCondition(com.example.battlesimulator.model.enums.StatusCondition.NONE);
                    pokemon.setSleepCounter(0);
                    pokemon.setToxicCounter(0);
                    log.add(pokemon.getNickname() + " shed its status condition!");
                }
            }
            case MOODY -> applyMoody(pokemon, log);
            default -> {}
        }
    }

    private void applyOnBeingHitEffects(BattlePokemon attacker, BattlePokemon defender,
                                        Move move, BattleSession session, List<String> log) {
        Ability defenderAbility = defender.getAbility();
        Type moveType = resolveMoveType(attacker, move);
        if (defender.getHeldItem() == HeldItem.AIR_BALLOON && !defender.isAirBalloonPopped()) {
            defender.setAirBalloonPopped(true);
            log.add(defender.getNickname() + "'s Air Balloon popped!");
        }
        if (defender.getHeldItem() == HeldItem.WEAKNESS_POLICY
                && !defender.isWeaknessPolicyUsed()
                && isSuperEffectiveHit(moveType, defender)) {
            defender.setWeaknessPolicyUsed(true);
            applyStage(defender, "attack", 2, log);
            applyStage(defender, "specialAttack", 2, log);
            log.add(defender.getNickname() + "'s Weakness Policy sharply boosted its offenses!");
        }
        if (defenderAbility == null) return;
        switch (defenderAbility) {
            case WEAK_ARMOR -> {
                if (move.category() == com.example.battlesimulator.model.enums.MoveCategory.PHYSICAL) {
                    applyStage(defender, "defense", -1, log);
                    applyStage(defender, "speed", 2, log);
                }
            }
            case STAMINA -> applyStage(defender, "defense", 1, log);
            case STEAM_ENGINE -> {
                if (moveType == Type.FIRE || moveType == Type.WATER) {
                    applyStage(defender, "speed", 6, log);
                }
            }
            case JUSTIFIED -> {
                if (moveType == Type.DARK) {
                    applyStage(defender, "attack", 1, log);
                }
            }
            case RATTLED -> {
                if (moveType == Type.DARK || moveType == Type.BUG || moveType == Type.GHOST) {
                    applyStage(defender, "speed", 1, log);
                }
            }
            case COLOR_CHANGE -> {
                defender.setType1(moveType);
                defender.setType2(Type.NONE);
                log.add(defender.getNickname() + "'s Color Change made it " + moveType.name() + "-type!");
            }
            case TOXIC_DEBRIS -> {
                if (isContactMove(move)) {
                    if (isPlayer1Active(defender, session)) {
                        session.setToxicSpikesPlayer2(Math.min(2, session.getToxicSpikesPlayer2() + 1));
                    } else {
                        session.setToxicSpikesPlayer1(Math.min(2, session.getToxicSpikesPlayer1() + 1));
                    }
                    log.add(defender.getNickname() + "'s Toxic Debris scattered Toxic Spikes!");
                }
            }
            default -> {}
        }
    }

    /**
     * Applies on-contact ability effects on the ATTACKER after a contact move lands.
     * Handles: Static, Flame Body, Poison Point, Rough Skin, Iron Barbs, Effect Spore.
     */
    private void applyOnContactAbilityEffects(BattlePokemon attacker, BattlePokemon defender,
                                              Move move, List<String> log) {
        if (!isContactMove(move) || defender.getAbility() == null) return;
        if (attacker.getAbility() == Ability.POISON_TOUCH && rng.nextInt(10) < 3) {
            applyStatus(defender, com.example.battlesimulator.model.enums.StatusCondition.POISON, attacker, log);
        }
        switch (defender.getAbility()) {
            case STATIC -> {
                if (rng.nextInt(10) < 3) { // 30%
                    applyStatus(attacker, com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS, log);
                    log.add(defender.getNickname() + "'s Static paralyzed " + attacker.getNickname() + "!");
                }
            }
            case FLAME_BODY -> {
                if (rng.nextInt(10) < 3) {
                    applyStatus(attacker, com.example.battlesimulator.model.enums.StatusCondition.BURN, log);
                    log.add(defender.getNickname() + "'s Flame Body burned " + attacker.getNickname() + "!");
                }
            }
            case POISON_POINT -> {
                if (rng.nextInt(10) < 3) {
                    applyStatus(attacker, com.example.battlesimulator.model.enums.StatusCondition.POISON, log);
                    log.add(defender.getNickname() + "'s Poison Point poisoned " + attacker.getNickname() + "!");
                }
            }
            case ROUGH_SKIN, IRON_BARBS -> {
                int dmg = Math.max(1, attacker.getMaxHp() / 8);
                attacker.setCurrentHp(Math.max(0, attacker.getCurrentHp() - dmg));
                log.add(defender.getNickname() + "'s " + defender.getAbility().getDisplayName()
                        + " hurt " + attacker.getNickname() + "! (−" + dmg + " HP)");
            }
            case EFFECT_SPORE -> {
                if (rng.nextInt(10) < 3) {
                    int roll = rng.nextInt(3);
                    com.example.battlesimulator.model.enums.StatusCondition sc = switch (roll) {
                        case 0 -> com.example.battlesimulator.model.enums.StatusCondition.SLEEP;
                        case 1 -> com.example.battlesimulator.model.enums.StatusCondition.POISON;
                        default -> com.example.battlesimulator.model.enums.StatusCondition.PARALYSIS;
                    };
                    applyStatus(attacker, sc, log);
                    log.add(defender.getNickname() + "'s Effect Spore affected " + attacker.getNickname() + "!");
                }
            }
            default -> {}
        }
    }

    /** Returns true if the move is sound-based (for Soundproof). */
    private boolean isSoundMove(Move move) {
        java.util.Set<String> soundMoves = java.util.Set.of(
                "hyper-voice", "boomburst", "bug-buzz", "chatter", "echoed-voice",
                "grass-whistle", "growl", "heal-bell", "metal-sound", "perish-song",
                "relic-song", "screech", "sing", "snore", "sparkling-aria",
                "supersonic", "uproar", "round"
        );
        return soundMoves.contains(move.id().toLowerCase());
    }

    private boolean isBallOrBombMove(Move move) {
        return java.util.Set.of(
                "shadow-ball", "energy-ball", "electro-ball", "weather-ball", "focus-blast",
                "sludge-bomb", "seed-bomb", "gyro-ball", "mist-ball", "pyro-ball",
                "magnet-bomb", "ice-ball", "bullet-ball"
        ).contains(move.id().toLowerCase());
    }

    private boolean isWindMove(Move move) {
        return java.util.Set.of(
                "air-slash", "bleakwind-storm", "gust", "hurricane", "heat-wave",
                "icy-wind", "petal-blizzard", "springtide-storm", "twister", "whirlwind"
        ).contains(move.id().toLowerCase());
    }

    private void applyMoody(BattlePokemon pokemon, List<String> log) {
        List<String> stats = new ArrayList<>(List.of("attack", "defense", "specialAttack", "specialDefense", "speed", "accuracy", "evasion"));
        String up = stats.get(rng.nextInt(stats.size()));
        stats.remove(up);
        String down = stats.get(rng.nextInt(stats.size()));
        applyStage(pokemon, up, 2, log);
        applyStage(pokemon, down, -1, log);
    }

    private void applyOnKnockoutAbilityEffects(BattlePokemon attacker, List<String> log) {
        if (attacker == null || attacker.getAbility() == null) return;
        switch (attacker.getAbility()) {
            case MOXIE -> applyStage(attacker, "attack", 1, log);
            case BEAST_BOOST -> applyStage(attacker, highestNonHpStat(attacker), 1, log);
            default -> {}
        }
    }

    private String highestNonHpStat(BattlePokemon pokemon) {
        int atk = pokemon.getAttack();
        int def = pokemon.getDefense();
        int spa = pokemon.getSpecialAttack();
        int spd = pokemon.getSpecialDefense();
        int spe = pokemon.getSpeed();
        int max = Math.max(Math.max(Math.max(atk, def), Math.max(spa, spd)), spe);
        if (spe == max) return "speed";
        if (spa == max) return "specialAttack";
        if (atk == max) return "attack";
        if (spd == max) return "specialDefense";
        return "defense";
    }

    // -------------------------------------------------------------------------
    // HELD ITEM EFFECTS
    // -------------------------------------------------------------------------

    /**
     * End-of-turn held item effects: Leftovers, Black Sludge, berries,
     * Flame Orb, Toxic Orb.
     */
    private void applyEndOfTurnItemEffects(BattlePokemon pokemon, List<String> log) {
        switch (pokemon.getHeldItem()) {
            case LEFTOVERS -> {
                if (pokemon.getCurrentHp() < pokemon.getMaxHp()) {
                    int heal = Math.max(1, pokemon.getMaxHp() / 16);
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                    log.add(pokemon.getNickname() + " restored HP with Leftovers! (+" + heal + " HP)");
                }
            }
            case BLACK_SLUDGE -> {
                boolean isPoison = pokemon.getType1() == com.example.battlesimulator.model.enums.Type.POISON
                        || pokemon.getType2() == com.example.battlesimulator.model.enums.Type.POISON;
                if (isPoison) {
                    if (pokemon.getCurrentHp() < pokemon.getMaxHp()) {
                        int heal = Math.max(1, pokemon.getMaxHp() / 16);
                        pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                        log.add(pokemon.getNickname() + " restored HP with Black Sludge! (+" + heal + " HP)");
                    }
                } else {
                    int dmg = Math.max(1, pokemon.getMaxHp() / 8);
                    pokemon.setCurrentHp(Math.max(0, pokemon.getCurrentHp() - dmg));
                    log.add(pokemon.getNickname() + " is hurt by Black Sludge! (−" + dmg + " HP)");
                }
            }
            case SITRUS_BERRY -> {
                if (!pokemon.isBerryUsed() && pokemon.getCurrentHp() <= pokemon.getMaxHp() / 2) {
                    int heal = pokemon.getMaxHp() / 4;
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + heal));
                    pokemon.setBerryUsed(true);
                    log.add(pokemon.getNickname() + " ate its Sitrus Berry! (+" + heal + " HP)");
                    if (pokemon.getAbility() == Ability.UNBURDEN) { pokemon.setUnburdened(true); log.add(pokemon.getNickname() + "'s Unburden doubled its Speed!"); }
                }
            }
            case ORAN_BERRY -> {
                if (!pokemon.isBerryUsed() && pokemon.getCurrentHp() <= pokemon.getMaxHp() / 2) {
                    pokemon.setCurrentHp(Math.min(pokemon.getMaxHp(), pokemon.getCurrentHp() + 10));
                    pokemon.setBerryUsed(true);
                    log.add(pokemon.getNickname() + " ate its Oran Berry! (+10 HP)");
                    if (pokemon.getAbility() == Ability.UNBURDEN) { pokemon.setUnburdened(true); log.add(pokemon.getNickname() + "'s Unburden doubled its Speed!"); }
                }
            }
            case LUM_BERRY -> {
                if (!pokemon.isBerryUsed()
                        && pokemon.getStatusCondition() != com.example.battlesimulator.model.enums.StatusCondition.NONE) {
                    pokemon.setStatusCondition(com.example.battlesimulator.model.enums.StatusCondition.NONE);
                    pokemon.setSleepCounter(0);
                    pokemon.setToxicCounter(0);
                    pokemon.setBerryUsed(true);
                    log.add(pokemon.getNickname() + "'s Lum Berry cured its status condition!");
                    if (pokemon.getAbility() == Ability.UNBURDEN) { pokemon.setUnburdened(true); log.add(pokemon.getNickname() + "'s Unburden doubled its Speed!"); }
                }
            }
            case FLAME_ORB -> applyStatus(pokemon,
                    com.example.battlesimulator.model.enums.StatusCondition.BURN, log);
            case TOXIC_ORB -> applyStatus(pokemon,
                    com.example.battlesimulator.model.enums.StatusCondition.TOXIC, log);
            default -> {}
        }
    }

    /**
     * Returns true for moves that make physical contact (used for Rocky Helmet).
     * This is a non-exhaustive but representative list.
     */
    private boolean isContactMove(Move move) {
        if (move.category() != com.example.battlesimulator.model.enums.MoveCategory.PHYSICAL) return false;
        // A small set of non-contact physical moves
        java.util.Set<String> nonContact = java.util.Set.of(
                "earthquake", "magnitude", "rock-slide", "rock-blast",
                "bullet-seed", "pin-missile", "icicle-spear", "surf",
                "self-destruct", "explosion", "poison-sting"
        );
        return !nonContact.contains(move.id().toLowerCase());
    }
    private boolean hasTrappingAbility(BattlePokemon p) {
        return p.getAbility() == Ability.ARENA_TRAP
                || p.getAbility() == Ability.SHADOW_TAG
                || p.getAbility() == Ability.MAGNET_PULL;
    }

    /** Returns true if the given BattlePokemon object is the current player1 active. */
    private boolean isPlayer1Active(BattlePokemon pokemon, BattleSession session) {
        return session.getPlayer1Active() != null && session.getPlayer1Active() == pokemon;
    }

    // -------------------------------------------------------------------------
    // MOVE PROPERTY HELPERS
    // -------------------------------------------------------------------------

    /** Returns true if this move requires a charging turn before dealing damage. */
    private boolean isTwoTurnMove(String moveId) {
        return switch (moveId) {
            case "fly", "bounce", "dig", "dive", "solar-beam", "solar-blade",
                 "sky-attack", "skull-bash", "phantom-force", "shadow-force",
                 "geomancy", "freeze-shock", "ice-burn", "razor-wind" -> true;
            default -> false;
        };
    }

    private SemiInvulnerableResolution resolveSemiInvulnerability(Move move, BattlePokemon defender) {
        String chargingMove = defender.getChargingMove();
        if (chargingMove == null) {
            return SemiInvulnerableResolution.NORMAL;
        }

        String moveId = move.id().toLowerCase();
        return switch (chargingMove) {
            case "fly", "bounce" -> {
                if (moveId.equals("gust") || moveId.equals("twister")) {
                    yield new SemiInvulnerableResolution(false, 2.0, "flying");
                }
                if (moveId.equals("thunder") || moveId.equals("hurricane")
                        || moveId.equals("sky-uppercut") || moveId.equals("smack-down")
                        || moveId.equals("thousand-arrows")) {
                    yield new SemiInvulnerableResolution(false, 1.0, "flying");
                }
                yield new SemiInvulnerableResolution(true, 1.0, "flying");
            }
            case "dig" -> {
                if (moveId.equals("earthquake") || moveId.equals("magnitude")) {
                    yield new SemiInvulnerableResolution(false, 2.0, "underground");
                }
                yield new SemiInvulnerableResolution(true, 1.0, "underground");
            }
            case "dive" -> {
                if (moveId.equals("surf") || moveId.equals("whirlpool")) {
                    yield new SemiInvulnerableResolution(false, 2.0, "underwater");
                }
                yield new SemiInvulnerableResolution(true, 1.0, "underwater");
            }
            case "phantom-force", "shadow-force" -> new SemiInvulnerableResolution(true, 1.0, "vanished");
            default -> SemiInvulnerableResolution.NORMAL;
        };
    }

    private record SemiInvulnerableResolution(boolean blocked, double damageMultiplier, String stateText) {
        private static final SemiInvulnerableResolution NORMAL = new SemiInvulnerableResolution(false, 1.0, "");
    }

    /**
     * If this move deals fixed damage (ignoring Attack/Defense), returns the damage amount.
     * Returns null if the move uses the normal damage formula.
     */
    private Integer getFixedDamage(BattlePokemon attacker, BattlePokemon defender, Move move) {
        return switch (move.id().toLowerCase()) {
            case "seismic-toss", "night-shade"  -> attacker.getLevel();
            case "dragon-rage"                   -> 40;
            case "sonic-boom"                    -> 20;
            case "psywave"                       -> Math.max(1, attacker.getLevel() * (5 + rng.nextInt(11)) / 10);
            case "super-fang"                    -> Math.max(1, defender.getCurrentHp() / 2);
            case "endeavor"                      -> Math.max(0, defender.getCurrentHp() - attacker.getCurrentHp());
            case "final-gambit"                  -> { int hp = attacker.getCurrentHp(); attacker.setCurrentHp(0); yield hp; }
            case "nature's-madness", "ruination", "natures-madness" -> Math.max(1, defender.getCurrentHp() / 2);
            default -> null;
        };
    }

    /**
     * Returns how many times a multi-hit move hits.
     * Returns 1 for single-hit moves.
     */
    private int getMultiHitCount(Move move) {
        // Skill Link guarantees maximum hits for standard variable multi-hit moves.
        // Caller context does not pass the attacker here, so specific always-5 move ids
        // remain handled explicitly elsewhere.
        return switch (move.id().toLowerCase()) {
            // Always 2 hits
            case "bonemerang", "double-hit", "double-iron-bash", "dual-chop",
                 "dual-wingbeat", "gear-grind", "twin-needle", "twineedle",
                 "double-kick", "dragon-darts" -> 2;
            // Always 3 hits
            case "triple-kick", "triple-axel" -> 3;
            // Always 5 hits
            case "bullet-seed", "icicle-spear", "rock-blast", "water-shuriken",
                 "pin-missile" -> 5;
            // Fury Attack / Fury Swipes / Comet Punch: 2-5 hits (avg 3)
            case "fury-attack", "fury-swipes", "comet-punch", "spike-cannon",
                 "barrage", "arm-thrust" -> 2 + rng.nextInt(4);
            // Scale Shot, Tail Slap, etc.: 2-5 hits
            case "scale-shot", "tail-slap", "crush-grip" -> 2 + rng.nextInt(4);
            // Population Bomb: 1-10 hits
            case "population-bomb" -> 1 + rng.nextInt(10);
            // Skill Link-boosted moves always 5 (we can't check here; approximating)
            default -> 1;
        };
    }

    private int getSkillLinkHitCount(Move move) {
        return switch (move.id().toLowerCase()) {
            case "bullet-seed", "icicle-spear", "rock-blast", "water-shuriken",
                 "pin-missile", "fury-attack", "fury-swipes", "comet-punch",
                 "spike-cannon", "barrage", "arm-thrust", "scale-shot",
                 "tail-slap", "population-bomb" -> 5;
            default -> getMultiHitCount(move);
        };
    }

    /**
     * Returns the HP drain percentage for draining moves (0 = not a drain move).
     * Standard drain is 50% of damage dealt; some moves use 75%.
     */
    private int getDrainPercent(Move move) {
        return switch (move.id().toLowerCase()) {
            case "absorb", "mega-drain", "giga-drain", "leech-life",
                 "drain-punch", "draining-kiss", "horn-leech",
                 "oblivion-wing", "parabolic-charge" -> 50;
            case "dream-eater" -> 50; // also requires opponent to be asleep
            default -> 0;
        };
    }

    // -------------------------------------------------------------------------
    // ENTRY HAZARDS
    // -------------------------------------------------------------------------

    /**
     * Applies entry hazards to a Pokemon switching in on the given player's side.
     * playerNum: 1 or 2 — which player's side the incoming mon lands on.
     *
     * - Stealth Rock: always triggers, Rock-effectiveness scaled (1/8 * eff)
     * - Spikes: ground-based; 1 layer=1/8 HP, 2=1/6, 3=1/4
     * - Toxic Spikes: ground-based; 1 layer=Poison, 2=Toxic; Poison-type absorbs
     * - Sticky Web: ground-based; -1 Speed stage
     * Flying-types and Levitate are immune to all ground-based hazards.
     * Heavy-Duty Boots negate all hazards.
     */
    private void applyEntryHazards(BattlePokemon incoming, int playerNum,
                                   BattleSession session, List<String> log) {
        if (incoming == null || incoming.isFainted()) return;

        // Heavy-Duty Boots negate all entry hazards
        if (incoming.getHeldItem() == HeldItem.HEAVY_DUTY_BOOTS) {
            log.add(incoming.getNickname() + "'s Heavy-Duty Boots protect it from hazards!");
            return;
        }

        com.example.battlesimulator.model.enums.Type t1 = incoming.getType1();
        com.example.battlesimulator.model.enums.Type t2 = incoming.getType2();

        boolean isFlying   = t1 == com.example.battlesimulator.model.enums.Type.FLYING
                || t2 == com.example.battlesimulator.model.enums.Type.FLYING;
        boolean isLevitate = incoming.getAbility() == Ability.LEVITATE;
        boolean groundImmune = isFlying || isLevitate;

        int    spikes      = (playerNum == 1) ? session.getSpikesPlayer1()       : session.getSpikesPlayer2();
        int    toxicSpikes = (playerNum == 1) ? session.getToxicSpikesPlayer1()  : session.getToxicSpikesPlayer2();
        boolean sRock      = (playerNum == 1) ? session.isStealthRockPlayer1()   : session.isStealthRockPlayer2();
        boolean stickyWeb  = (playerNum == 1) ? session.isStickyWebPlayer1()     : session.isStickyWebPlayer2();

        // ── Stealth Rock — always hits, effectiveness-scaled ─────────────
        if (sRock) {
            com.example.battlesimulator.model.enums.Type safeT2 =
                    (t2 != null) ? t2 : com.example.battlesimulator.model.enums.Type.NONE;
            double eff = com.example.battlesimulator.util.TypeChart.getMultiplier(
                    com.example.battlesimulator.model.enums.Type.ROCK, t1)
                    * com.example.battlesimulator.util.TypeChart.getMultiplier(
                    com.example.battlesimulator.model.enums.Type.ROCK, safeT2);
            int damage = Math.max(1, (int)(incoming.getMaxHp() * 0.125 * eff));
            incoming.setCurrentHp(Math.max(0, incoming.getCurrentHp() - damage));
            log.add(incoming.getNickname() + " was hurt by Stealth Rock! (−" + damage + " HP)");
        }

        // Ground-immune mons skip all remaining hazards
        if (groundImmune) return;

        // ── Spikes ────────────────────────────────────────────────────────
        if (spikes > 0) {
            int[] divisors = {8, 6, 4};
            int damage = Math.max(1, incoming.getMaxHp() / divisors[spikes - 1]);
            incoming.setCurrentHp(Math.max(0, incoming.getCurrentHp() - damage));
            log.add(incoming.getNickname() + " was hurt by Spikes! (−" + damage + " HP)");
        }

        // ── Toxic Spikes ─────────────────────────────────────────────────
        if (toxicSpikes > 0) {
            boolean isPoison = t1 == com.example.battlesimulator.model.enums.Type.POISON
                    || t2 == com.example.battlesimulator.model.enums.Type.POISON;
            boolean isSteel  = t1 == com.example.battlesimulator.model.enums.Type.STEEL
                    || t2 == com.example.battlesimulator.model.enums.Type.STEEL;

            if (isPoison) {
                // Poison-type absorbs the Toxic Spikes, clearing them
                if (playerNum == 1) session.setToxicSpikesPlayer1(0);
                else                session.setToxicSpikesPlayer2(0);
                log.add(incoming.getNickname() + " absorbed the Toxic Spikes!");
            } else if (!isSteel) {
                com.example.battlesimulator.model.enums.StatusCondition sc =
                        (toxicSpikes == 1)
                                ? com.example.battlesimulator.model.enums.StatusCondition.POISON
                                : com.example.battlesimulator.model.enums.StatusCondition.TOXIC;
                applyStatus(incoming, sc, log);
            }
        }

        // ── Sticky Web — lowers Speed by 1 stage ─────────────────────────
        if (stickyWeb) {
            log.add(incoming.getNickname() + " was caught in a Sticky Web!");
            applyStage(incoming, "speed", -1, log);
        }
    }

    // -------------------------------------------------------------------------
    // FLINCH HELPER
    // -------------------------------------------------------------------------

    /**
     * Applies flinch to the defender if the move has a flinch chance.
     * Inner Focus blocks flinching. Only applies if defender hasn't moved yet this turn
     * (handled naturally since flinch is set and checked at move-execution time).
     */
    private void applyFlinchEffect(BattlePokemon attacker, BattlePokemon defender,
                                   Move move, List<String> log) {
        if (defender.getAbility() == Ability.INNER_FOCUS) return;
        int flinchChance = getFlinchChance(move);
        if (flinchChance > 0 && rng.nextInt(100) < flinchChance) {
            defender.setFlinched(true);
            // Note: flinch only matters if defender hasn't moved yet this turn.
            // The engine resolves faster mon first, so slower mon may still flinch usefully.
        }
    }

    /** Returns the % flinch chance of a move (0 if none). */
    private int getFlinchChance(Move move) {
        return switch (move.id().toLowerCase()) {
            case "air-slash", "astonish", "bite", "bone-club", "dark-pulse",
                 "dragon-rush", "extrasensory", "fake-out", "fire-fang",
                 "headbutt", "hyper-fang", "ice-fang", "iron-head",
                 "needle-arm", "rock-slide", "rolling-kick", "snore",
                 "stomp", "thunder-fang", "twister", "waterfall",
                 "zen-headbutt", "icicle-crash", "sky-attack" -> 30;
            case "king-shield", "ancientpower", "silver-wind", "ominous-wind" -> 10;
            default -> 0;
        };
    }

    // -------------------------------------------------------------------------
    // CONFUSION & TAUNT HELPERS
    // -------------------------------------------------------------------------

    /**
     * Inflicts confusion on the target for 2-5 turns.
     * Own Tempo is immune. Fails if already confused.
     */
    private void applyConfusion(BattlePokemon target, List<String> log) {
        if (target.getAbility() == Ability.OWN_TEMPO) {
            log.add(target.getNickname() + "'s Own Tempo prevents confusion!");
            return;
        }
        if (target.isConfused()) {
            log.add(target.getNickname() + " is already confused!");
            return;
        }
        int turns = 2 + rng.nextInt(4); // 2-5 turns
        target.setConfused(true);
        target.setConfusionCounter(turns);
        log.add(target.getNickname() + " became confused!");
    }

    /**
     * Inflicts Taunt on the target for 3 turns.
     * Oblivious is immune. Fails if already taunted.
     */
    private void applyTaunt(BattlePokemon target, List<String> log) {
        if (target.getAbility() == Ability.OBLIVIOUS) {
            log.add(target.getNickname() + "'s Oblivious prevents Taunt!");
            return;
        }
        if (target.isTaunted()) {
            log.add(target.getNickname() + " is already taunted!");
            return;
        }
        target.setTaunted(true);
        target.setTauntCounter(3);
        log.add(target.getNickname() + " fell for the taunt! It can only use damaging moves for 3 turns!");
    }

    /**
     * Counts down taunt duration at end of each turn.
     * Confusion counts down at move-use time instead.
     */
    private void tickVolatileConditions(BattlePokemon pokemon, List<String> log) {
        // Flinch only lasts within a single turn; clear it at end of turn
        pokemon.setFlinched(false);
        if (pokemon.isTaunted()) {
            int remaining = pokemon.getTauntCounter() - 1;
            if (remaining <= 0) {
                pokemon.setTaunted(false);
                pokemon.setTauntCounter(0);
                log.add(pokemon.getNickname() + " is no longer under the effects of Taunt!");
            } else {
                pokemon.setTauntCounter(remaining);
            }
        }
    }

}
