package com.example.battlesimulator.model.enums;

/**
 * Held items that can be equipped to a Pokemon.
 * Each item has a display name and description of its effect.
 */
public enum HeldItem {
    NONE("None", "No held item."),

    // Damage-boosting items
    CHOICE_BAND("Choice Band", "Boosts Attack by 1.5x, but locks into one move."),
    CHOICE_SPECS("Choice Specs", "Boosts Sp. Atk by 1.5x, but locks into one move."),
    CHOICE_SCARF("Choice Scarf", "Boosts Speed by 1.5x, but locks into one move."),
    LIFE_ORB("Life Orb", "Boosts all moves by 1.3x; user loses 1/10 max HP per attack."),
    EXPERT_BELT("Expert Belt", "Boosts super-effective moves by 1.2x."),
    MUSCLE_BAND("Muscle Band", "Boosts physical moves by 1.1x."),
    WISE_GLASSES("Wise Glasses", "Boosts special moves by 1.1x."),
    LIGHT_BALL("Light Ball", "Doubles Attack and Sp. Atk for Pikachu only."),
    LEEK("Leek", "Raises critical-hit ratio for Farfetch'd and Sirfetch'd."),
    METRONOME("Metronome", "Boosts consecutive uses of the same move."),

    // Berries and passive recovery
    SITRUS_BERRY("Sitrus Berry", "Restores 25% max HP when HP drops to 50% or below."),
    ORAN_BERRY("Oran Berry", "Restores 10 HP when HP drops to 50% or below."),
    LUM_BERRY("Lum Berry", "Cures any status condition once."),
    SALAC_BERRY("Salac Berry", "Raises Speed by 1 stage at low HP."),
    LIECHI_BERRY("Liechi Berry", "Raises Attack by 1 stage at low HP."),
    PETAYA_BERRY("Petaya Berry", "Raises Sp. Atk by 1 stage at low HP."),
    APICOT_BERRY("Apicot Berry", "Raises Sp. Def by 1 stage at low HP."),
    LANSAT_BERRY("Lansat Berry", "Raises critical-hit ratio at low HP."),
    STARF_BERRY("Starf Berry", "Sharply raises a random stat at low HP."),
    MICLE_BERRY("Micle Berry", "Boosts the accuracy of the next move at low HP."),
    CUSTAP_BERRY("Custap Berry", "Lets the holder move earlier at low HP."),
    ROWAP_BERRY("Rowap Berry", "Damages an attacker after a special hit."),
    JABOCA_BERRY("Jaboca Berry", "Damages an attacker after a physical hit."),
    LEFTOVERS("Leftovers", "Restores 1/16 max HP at the end of each turn."),
    BLACK_SLUDGE("Black Sludge", "Restores 1/16 HP/turn for Poison types; damages others by 1/8."),
    BIG_ROOT("Big Root", "Boosts HP drained by draining moves."),
    SHELL_BELL("Shell Bell", "Restores HP equal to 1/8 of damage dealt."),

    // Defensive and reactive items
    ROCKY_HELMET("Rocky Helmet", "Deals 1/6 max HP damage to attackers that make contact."),
    ASSAULT_VEST("Assault Vest", "Boosts Sp. Def by 1.5x; holder cannot use status moves."),
    EVIOLITE("Eviolite", "Boosts Defense and Sp. Def by 1.5x (unevolved Pokemon only)."),
    FOCUS_SASH("Focus Sash", "Survives a KO hit from full HP with 1 HP remaining (once)."),
    WEAKNESS_POLICY("Weakness Policy", "Raises Attack and Sp. Atk by 2 stages when hit super effectively."),
    AIR_BALLOON("Air Balloon", "Grants immunity to Ground moves until hit."),
    POWER_HERB("Power Herb", "Consumes to skip the charge turn of a two-turn move."),
    WHITE_HERB("White Herb", "Restores lowered stats once."),
    MENTAL_HERB("Mental Herb", "Removes Taunt and similar mental effects once."),
    KINGS_ROCK("King's Rock", "May cause flinching on damaging moves."),
    RAZOR_FANG("Razor Fang", "May cause flinching on damaging moves."),
    ABSORB_BULB("Absorb Bulb", "Raises Sp. Atk when hit by a Water move."),
    THROAT_SPRAY("Throat Spray", "Raises Sp. Atk after using a sound move."),
    EJECT_BUTTON("Eject Button", "Forces the holder to switch out when hit."),
    RED_CARD("Red Card", "Forces the attacker to switch out when hit."),
    ZOOM_LENS("Zoom Lens", "Boosts accuracy when moving after the target."),
    WIDE_LENS("Wide Lens", "Boosts move accuracy."),

    // Speed and status items
    IRON_BALL("Iron Ball", "Halves the holder's Speed."),
    HEAVY_DUTY_BOOTS("Heavy-Duty Boots", "Protects the holder from all entry hazard damage and effects."),
    FLAME_ORB("Flame Orb", "Burns the holder at the end of the turn."),
    TOXIC_ORB("Toxic Orb", "Badly poisons the holder at the end of the turn."),

    // Mega Stones
    VENUSAURITE("Venusaurite", "Allows Venusaur to Mega Evolve."),
    CHARIZARDITE_X("Charizardite X", "Allows Charizard to Mega Evolve into Mega Charizard X."),
    CHARIZARDITE_Y("Charizardite Y", "Allows Charizard to Mega Evolve into Mega Charizard Y."),
    BLASTOISINITE("Blastoisinite", "Allows Blastoise to Mega Evolve."),
    ALAKAZITE("Alakazite", "Allows Alakazam to Mega Evolve."),
    GARDEVOIRITE("Gardevoirite", "Allows Gardevoir to Mega Evolve."),
    MAWILITE("Mawilite", "Allows Mawile to Mega Evolve."),
    LUCARIONITE("Lucarionite", "Allows Lucario to Mega Evolve."),
    SCIZORITE("Scizorite", "Allows Scizor to Mega Evolve."),
    TYRANITARITE("Tyranitarite", "Allows Tyranitar to Mega Evolve."),
    GARCHOMPITE("Garchompite", "Allows Garchomp to Mega Evolve."),
    SALAMENCITE("Salamencite", "Allows Salamence to Mega Evolve."),
    SABLENITE("Sablenite", "Allows Sableye to Mega Evolve."),
    METAGROSSITE("Metagrossite", "Allows Metagross to Mega Evolve.");

    private final String displayName;
    private final String description;

    HeldItem(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
