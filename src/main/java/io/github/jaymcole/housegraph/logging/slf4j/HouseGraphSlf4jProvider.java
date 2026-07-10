package io.github.jaymcole.housegraph.logging.slf4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The SLF4J 2.x service provider that binds SLF4J to HouseGraph's logging system. SLF4J
 * discovers it via {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider} on the
 * classpath, so simply shipping this class (and that service file) makes every SLF4J logger
 * — including JDA's — route through {@link HouseGraphLoggerFactory} into {@code LogManager}.
 *
 * <p>Marker and MDC support use SLF4J's stock in-memory helpers; HouseGraph's sinks don't
 * consume markers or MDC, but providing real implementations keeps well-behaved libraries
 * that touch them working.
 */
public final class HouseGraphSlf4jProvider implements SLF4JServiceProvider {

    /** The SLF4J API version this provider is built and tested against. */
    public static final String REQUESTED_API_VERSION = "2.0.13";

    private ILoggerFactory loggerFactory;
    private IMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        loggerFactory = new HouseGraphLoggerFactory();
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new BasicMDCAdapter();
    }
}
