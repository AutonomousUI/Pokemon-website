package com.example.battlesimulator.model;

import com.example.battlesimulator.model.enums.Ability;
import com.example.battlesimulator.model.enums.HeldItem;
import com.example.battlesimulator.model.enums.Nature;
import com.example.battlesimulator.model.enums.StatusCondition;
import com.example.battlesimulator.model.enums.Type;
import lombok.Data;
import lombok.Builder;

import java.util.List;

@Data
@Builder
public class BattlePokemon {

    // Identity
    private String speciesId;
    private String originalSpeciesId;
    private String nickname;
    private int level;
    private Nature nature;
    private StatBlock ivs;
    private StatBlock evs;
    private Type type1;
    private Type type2;
    private Type baseType1;
    private Type baseType2;

    // Raw calculated stats at level
    private int maxHp;
    private int attack;
    private int defense;
    private int specialAttack;
    private int specialDefense;
    private int speed;

    // Volatile Battle State
    private int currentHp;

    // Non-volatile status condition
    @Builder.Default private StatusCondition statusCondition = StatusCondition.NONE;
    @Builder.Default private int toxicCounter = 0;
    @Builder.Default private int sleepCounter = 0;

    // Stat Stages (Range from -6 to +6)
    @Builder.Default private int attackStage = 0;
    @Builder.Default private int defenseStage = 0;
    @Builder.Default private int specialAttackStage = 0;
    @Builder.Default private int specialDefenseStage = 0;
    @Builder.Default private int speedStage = 0;
    @Builder.Default private int evasionStage = 0;
    @Builder.Default private int accuracyStage = 0;

    // Volatile conditions (reset on switch-out)
    @Builder.Default private boolean confused         = false;
    @Builder.Default private int     confusionCounter = 0;
    @Builder.Default private boolean taunted          = false;
    @Builder.Default private int     tauntCounter     = 0;
    @Builder.Default private boolean flinched         = false;   // Set during turn, cleared after move
    @Builder.Default private boolean trapped          = false;   // Cannot switch out
    @Builder.Default private int     critStageBonus   = 0;      // Extra crit stages (Super Luck)
    @Builder.Default private int     pressurePpDrain  = 0;      // Extra PP drain on opponents (Pressure — tracked on defender side)

    // Two-turn move charging state
    @Builder.Default private String  chargingMove     = null;   // move id being charged (null = not charging)

    // Protect state
    @Builder.Default private boolean protecting       = false;  // true for the turn Protect was used
    @Builder.Default private int     protectConsecutive = 0;   // consecutive protect turns (diminishing odds)

    // Substitute
    @Builder.Default private int     substituteHp     = 0;     // 0 = no substitute; >0 = sub HP remaining

    // Perish Song counter (0 = not counting, 1-3 turns remaining)
    @Builder.Default private int     perishCount      = 0;

    // Future Sight / Doom Desire delayed attack (turns remaining, 0 = none)
    @Builder.Default private int     futureSightTurns = 0;
    @Builder.Default private int     futureSightDmg   = 0;

    // Baton Pass — stat stages to be passed on switch (populated by Baton Pass handler)
    // Volatile stat stages transferred when using Baton Pass (null = nothing to pass)
    @Builder.Default private boolean batonPassPending = false;

    // U-turn/Volt Switch/Flip Turn — needs forced switch after damage
    @Builder.Default private boolean needsForcedSwitch = false;

    // Loadout
    private List<Move> moves;

    // Ability
    @Builder.Default private Ability ability = Ability.NONE;

    // Ability-triggered flags (volatile, reset on switch)
    @Builder.Default private boolean flashFireActive = false;
    @Builder.Default private boolean unburdened       = false;
    @Builder.Default private boolean disguiseIntact   = true;
    @Builder.Default private boolean iceFaceIntact    = true;
    @Builder.Default private boolean truantLoafing    = false;
    @Builder.Default private int     slowStartTurns   = 0;
    @Builder.Default private int     supremeOverlordStacks = 0;
    @Builder.Default private boolean movedThisTurn    = false;
    @Builder.Default private boolean analyticBoosted  = false;

    // Held Item
    @Builder.Default private HeldItem heldItem = HeldItem.NONE;

    // One-time use item flags
    @Builder.Default private boolean focusSashUsed  = false;
    @Builder.Default private boolean berryUsed      = false;
    @Builder.Default private boolean weaknessPolicyUsed = false;
    @Builder.Default private boolean airBalloonPopped = false;
    @Builder.Default private boolean megaEvolved = false;

    // Choice item lock
    private String choiceLock;

    // Helper method
    public boolean isFainted() {
        return this.currentHp <= 0;
    }
}
