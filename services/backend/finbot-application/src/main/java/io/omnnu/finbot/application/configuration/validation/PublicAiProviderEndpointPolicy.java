package io.omnnu.finbot.application.configuration.validation;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

public final class PublicAiProviderEndpointPolicy {
    private static final String ERROR_MESSAGE =
            "AI provider base URL must use a public HTTPS endpoint";

    private PublicAiProviderEndpointPolicy() {
    }

    public static URI parse(String value) {
        var normalized = Objects.requireNonNull(value, "baseUrl").strip();
        try {
            var uri = URI.create(normalized);
            var host = normalizeHost(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || isInternalHostname(host)
                    || isNonPublicIpLiteral(host)) {
                throw new IllegalArgumentException(ERROR_MESSAGE);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            if (ERROR_MESSAGE.equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException(ERROR_MESSAGE, exception);
        }
    }

    public static String normalize(String value) {
        return parse(value).toString();
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        var normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isInternalHostname(String host) {
        return "localhost".equals(host)
                || (!host.contains(".") && !host.contains(":"))
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || host.endsWith(".lan")
                || host.endsWith(".home.arpa")
                || host.endsWith(".svc")
                || host.endsWith(".cluster.local");
    }

    private static boolean isNonPublicIpLiteral(String host) {
        if (!isIpLiteral(host)) {
            return false;
        }
        try {
            var address = InetAddress.getByName(host);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || isUniqueLocalIpv6(address);
        } catch (UnknownHostException exception) {
            return true;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.contains(":")) {
            return true;
        }
        for (var index = 0; index < host.length(); index++) {
            var character = host.charAt(index);
            if (character != '.' && !Character.isDigit(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (address.getAddress()[0] & 0xfe) == 0xfc;
    }
}
