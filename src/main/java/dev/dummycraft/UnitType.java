package dev.dummycraft;

import java.util.Locale;

/** Small, readable unit roster for the strategy layer. */
public enum UnitType {
    INFANTRY("Infantry", "ground", 5, 0, 1, 0, 0, 2, 0, 0),
    TANK("Tank", "ground", 18, 2, 5, 0, 0, 4, 0, 0),
    ARTILLERY("Artillery", "ground", 14, 1, 6, 0, 0, 1, 0, 0),
    FIGHTER("Fighter", "air", 20, 4, 0, 4, 0, 1, 5, 0),
    BOMBER("Bomber", "air", 26, 6, 0, 7, 0, 0, 2, 0),
    SHIP("Ship", "water", 24, 5, 0, 0, 5, 0, 0, 5);

    public final String title, domain;
    public final int gold, oil, groundAttack, airAttack, waterAttack, groundDefense, airDefense, waterDefense;

    UnitType(String title, String domain, int gold, int oil, int groundAttack, int airAttack,
             int waterAttack, int groundDefense, int airDefense, int waterDefense) {
        this.title = title;
        this.domain = domain;
        this.gold = gold;
        this.oil = oil;
        this.groundAttack = groundAttack;
        this.airAttack = airAttack;
        this.waterAttack = waterAttack;
        this.groundDefense = groundDefense;
        this.airDefense = airDefense;
        this.waterDefense = waterDefense;
    }

    public static UnitType parse(String id) {
        if (id == null) return null;
        try { return valueOf(id.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }
}
