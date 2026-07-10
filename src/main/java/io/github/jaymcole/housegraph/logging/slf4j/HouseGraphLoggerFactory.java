package io.github.jaymcole.housegraph.logging.slf4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SLF4J {@link ILoggerFactory} that hands out {@link HouseGraphSlf4jLogger}s, cached by name
 * so repeated lookups for the same source return one instance (as SLF4J callers expect).
 */
final class HouseGraphLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, HouseGraphSlf4jLogger::new);
    }
}
