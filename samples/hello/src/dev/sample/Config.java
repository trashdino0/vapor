package dev.sample;

import java.io.Serializable;

public class Config implements Serializable {

    private static final long serialVersionUID = 1L;

    String name;
    int level;

    public Config(String name, int level) {
        this.name = name;
        this.level = level;
    }
}
