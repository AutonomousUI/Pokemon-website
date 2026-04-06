package com.example.battlesimulator.model.enums;

/**
 * Held items that can be equipped to a Pokémon.
 * Each item has a display name and description of its effect.
 */
public enum HeldItem {
    NONE("None", "No held item."),

    // ── Damage-boosting type plates / gems ───────────────────────────────
    CHOICE_BAND("Choice Band",        "Boosts Attack by 1.5×, but locks into one move."),
    CHOICE_SPECS("Choice Specs",      "Boosts Sp. Atk by 1.5×, but locks into one move."),
    CHOICE_SCARF("Choice Scarf",      "Boosts Speed by 1.5×, but locks into one move."),
    LIFE_ORB("Life Orb",              "Boosts all moves by 1.3×; user loses 1/10 max HP per attack."),
    EXPERT_BELT("Expert Belt",        "Boosts super-effective moves by 1.2×."),
    MUSCLE_BAND("Muscle Band",        "Boosts physical moves by 1.1×."),
    WISE_GLASSES("Wise Glasses",      "Boosts special moves by 1.1×."),
    LIGHT_BALL("Light Ball",          "Doubles Attack and Sp. Atk for Pikachu only."),
    LEEK("Leek",                      "Raises critical-hit ratio for Farfetch'd and Sirfetch'd."),

    // ── Berries ───────────────────────────────────────────────────────────
    SITRUS_BERRY("Sitrus Berry",      "Restores 25% max HP when HP drops to 50% or below."),
    ORAN_BERRY("Oran Berry",          "Restores 10 HP when HP drops to 50% or below."),
    LUM_BERRY("Lum Berry",            "Cures any status condition once."),
    LEFTOVERS("Leftovers",            "Restores 1/16 max HP at the end of each turn."),

    // ── Defensive items ───────────────────────────────────────────────────
    ROCKY_HELMET("Rocky Helmet",      "Deals 1/6 max HP damage to attackers that make contact."),
    ASSAULT_VEST("Assault Vest",      "Boosts Sp. Def by 1.5×; holder cannot use status moves."),
    EVIOLITE("Eviolite",              "Boosts Defense and Sp. Def by 1.5× (unevolved Pokémon only)."),
    FOCUS_SASH("Focus Sash",          "Survives a KO hit from full HP with 1 HP remaining (once)."),
    WEAKNESS_POLICY("Weakness Policy", "Raises Attack and Sp. Atk by 2 stages when hit super effectively."),
    AIR_BALLOON("Air Balloon",        "Grants immunity to Ground moves until hit."),
    POWER_HERB("Power Herb",          "Consumes to skip the charge turn of a two-turn move."),

    // ── Speed items ───────────────────────────────────────────────────────
    IRON_BALL("Iron Ball",            "Halves the holder's Speed."),

    // ── Hazard protection ────────────────────────────────────────────
    HEAVY_DUTY_BOOTS("Heavy-Duty Boots", "Protects the holder from all entry hazard damage and effects."),

    // ── Status / Utility ──────────────────────────────────────────────────
    BLACK_SLUDGE("Black Sludge",      "Restores 1/16 HP/turn for Poison types; damages others by 1/8."),
    FLAME_ORB("Flame Orb",            "Burns the holder at the end of the turn."),
    TOXIC_ORB("Toxic Orb",            "Badly poisons the holder at the end of the turn."),
    VENUSAURITE("Venusaurite",        "Allows Venusaur to Mega Evolve."),
    CHARIZARDITE_X("Charizardite X",  "Allows Charizard to Mega Evolve into Mega Charizard X."),
    CHARIZARDITE_Y("Charizardite Y",  "Allows Charizard to Mega Evolve into Mega Charizard Y."),
    BLASTOISINITE("Blastoisinite",    "Allows Blastoise to Mega Evolve."),
    ALAKAZITE("Alakazite",            "Allows Alakazam to Mega Evolve."),
    GARDEVOIRITE("Gardevoirite",      "Allows Gardevoir to Mega Evolve."),
    MAWILITE("Mawilite",              "Allows Mawile to Mega Evolve."),
    LUCARIONITE("Lucarionite",        "Allows Lucario to Mega Evolve."),
    SCIZORITE("Scizorite",            "Allows Scizor to Mega Evolve."),
    TYRANITARITE("Tyranitarite",      "Allows Tyranitar to Mega Evolve."),
    GARCHOMPITE("Garchompite",        "Allows Garchomp to Mega Evolve."),
    SALAMENCITE("Salamencite",        "Allows Salamence to Mega Evolve."),
    SABLENITE("Sablenite",            "Allows Sableye to Mega Evolve."),
    METAGROSSITE("Metagrossite",      "Allows Metagross to Mega Evolve.");

    private final String displayName;
    private final String description;

    HeldItem(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription()  { return description; }
}
