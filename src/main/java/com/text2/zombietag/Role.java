package com.text2.zombietag;

public enum Role {
    SURVIVOR,
    HOST_ZOMBIE,
    ZOMBIE;

    public boolean isZombie() {
        return this == HOST_ZOMBIE || this == ZOMBIE;
    }
}
