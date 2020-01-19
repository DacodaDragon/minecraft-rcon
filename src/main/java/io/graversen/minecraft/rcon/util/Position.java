package io.graversen.minecraft.rcon.util;

import org.apache.commons.text.StringSubstitutor;

import java.util.Map;

public class Position {
    private final Coordinate x;
    private final Coordinate y;
    private final Coordinate z;

    public Position(Coordinate x, Coordinate y, Coordinate z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Coordinate getX() {
        return x;
    }

    public Coordinate getY() {
        return y;
    }

    public Coordinate getZ() {
        return z;
    }

    @Override
    public String toString() {
        return StringSubstitutor.replace(
                "${x} ${y} ${z}",
                Map.of(
                        "x", getX().coordinate(),
                        "y", getY().coordinate(),
                        "z", getZ().coordinate()
                )
        );
    }
}
