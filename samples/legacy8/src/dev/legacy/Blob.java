package dev.legacy;

import java.io.Serializable;

public class Blob implements Serializable {

    private static final long serialVersionUID = 2L;

    String name;
    int level;

    public Blob(String name, int level) {
        this.name = name;
        this.level = level;
    }
}
