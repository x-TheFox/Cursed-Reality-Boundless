package io.github.xthefox.crb.ability;

/** A three-state input condition: must be true, must be false, or don't care. */
public enum Tri {
    YES, NO, ANY;

    public boolean test(boolean value) {
        return this == ANY || (this == YES) == value;
    }
}
