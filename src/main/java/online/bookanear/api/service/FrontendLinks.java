package online.bookanear.api.service;

import java.net.URI;

/** Validated frontend URLs; none of the user-controlled token values change the target origin. */
public final class FrontendLinks {
    private FrontendLinks() {}
    public static String base(String configured) {
        var uri = URI.create(configured);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("registration.home-url must be an absolute frontend URL");
        return configured.replaceAll("/+$", "");
    }
    public static String localized(String base, String path) {
        return base + (base.matches(".*/(cs|sk|pl|en)$") ? "" : "/cs") + path;
    }

    public static String localized(String base, String language, String path) {
        String normalizedBase = base.replaceFirst("/(cs|sk|pl|en)$", "");
        String locale = java.util.Set.of("cs", "sk", "pl", "en").contains(language) ? language : "cs";
        return normalizedBase + "/" + locale + path;
    }
}
