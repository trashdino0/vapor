package dev.vapor.pipeline.postprocess;

import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.pipeline.IPass;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Rewrites the resources that name classes textually so a renamed jar still
 * starts and still resolves services:
 *
 * <ul>
 *   <li>{@code META-INF/MANIFEST.MF}: {@code Main-Class} (and the agent
 *       launcher attributes) follow the class mapping;</li>
 *   <li>{@code META-INF/services/*}: file name (the interface) and each
 *       provider line follow the class mapping.</li>
 * </ul>
 *
 * <p>Everything else (nested {@code versions/} payloads, custom resources) is
 * left alone; {@code Class.forName("literal")} inside code is not rewritten on
 * purpose — use keep rules for reflective entry points.</p>
 */
public final class ResourceFixups implements IPass {

    private static final String SERVICES_PREFIX = "META-INF/services/";

    @Override
    public String name() {
        return "ResourceFixups";
    }

    @Override
    public void run(Context context) {
        context.checkCancelled();
        fixManifest(context);
        fixServices(context);
    }

    private static void fixManifest(Context context) {
        JarEntry.Resource manifestEntry = context.resource("META-INF/MANIFEST.MF");
        if (manifestEntry == null || manifestEntry.bytes().length == 0) {
            return;
        }
        Manifest manifest;
        try {
            manifest = new Manifest(new ByteArrayInputStream(manifestEntry.bytes()));
        } catch (Exception e) {
            System.err.println("  warn: unreadable manifest, left untouched: " + e.getMessage());
            return;
        }
        Attributes attributes = manifest.getMainAttributes();
        boolean changed = false;
        for (String attribute : List.of("Main-Class", "Launcher-Agent-Class", "Agent-Class")) {
            changed |= rewriteClassAttribute(context, attributes, attribute);
        }
        if (!changed) {
            return;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            manifest.write(buffer);
            context.replaceEntry(manifestEntry, new JarEntry.Resource(manifestEntry.name(), buffer.toByteArray()));
        } catch (Exception e) {
            System.err.println("  warn: manifest rewrite failed, left untouched: " + e.getMessage());
        }
    }

    private static boolean rewriteClassAttribute(Context context, Attributes attributes, String attributeName) {
        Attributes.Name name = new Attributes.Name(attributeName);
        String value = attributes.getValue(name);
        if (value == null || value.isEmpty()) {
            return false;
        }
        String internal = value.replace('.', '/');
        String mapped = context.mappings().mappedClass(internal);
        if (mapped.equals(internal)) {
            return false;
        }
        attributes.put(name, mapped.replace('/', '.'));
        return true;
    }

    private static void fixServices(Context context) {
        for (JarEntry entry : List.copyOf(context.entries())) {
            context.checkCancelled();
            if (!(entry instanceof JarEntry.Resource resource)
                    || resource.directory()
                    || !resource.name().startsWith(SERVICES_PREFIX)) {
                continue;
            }
            String interfaceName = resource.name().substring(SERVICES_PREFIX.length());
            String mappedInterface = context.mappings().mappedClass(interfaceName.replace('.', '/'));
            String newEntryName = SERVICES_PREFIX + mappedInterface.replace('/', '.');

            String text = new String(resource.bytes(), StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            StringBuilder rewritten = new StringBuilder(text.length());
            boolean changed = !newEntryName.equals(resource.name());
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    rewritten.append('\n');
                }
                String line = lines[i];
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    rewritten.append(line);
                    continue;
                }
                int hash = line.indexOf('#');
                String providerPart = hash < 0 ? line : line.substring(0, hash);
                String rest = hash < 0 ? "" : line.substring(hash);
                String providerName = providerPart.strip();
                String mappedProvider = context.mappings().mappedClass(providerName.replace('.', '/'));
                if (!mappedProvider.equals(providerName.replace('.', '/'))) {
                    rewritten.append(mappedProvider.replace('/', '.')).append(rest);
                    changed = true;
                } else {
                    rewritten.append(line);
                }
            }
            if (changed) {
                context.replaceEntry(entry,
                        new JarEntry.Resource(newEntryName, rewritten.toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
