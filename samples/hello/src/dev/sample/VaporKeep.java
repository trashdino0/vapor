package dev.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class, method or field whose name must survive obfuscation (e.g.
 * because it is looked up reflectively). Detected by simple name; the
 * annotation itself is stripped from the output.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface VaporKeep {
}
