package com.aitiniubi.medicalbooktranslator.translation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * Provider failover for long-running book translation.
 * The router never invents credentials: only configured providers are eligible.
 */
public final class TranslationRouter {
    public static final class Provider {
        public final String name;
        public final TranslationConfig config;
        public Provider(String name, TranslationConfig config) {
            this.name = name;
            this.config = config;
        }
    }

    private static final Map<String,Long> DISABLED_UNTIL = new ConcurrentHashMap<>();
    private static final long DAILY_QUOTA_COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    private static final long RATE_LIMIT_COOLDOWN_MS = 60L * 1000L;

    private TranslationRouter() {}

    public static String translate(String source, String context, List<Provider> providers) throws Exception {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("Chưa cấu hình AI provider nào có API key.");
        }

        List<String> failures = new ArrayList<>();
        for (Provider p : providers) {
            if (p == null || p.config == null || isBlank(p.config.endpoint) || isBlank(p.config.model) || isBlank(p.config.apiKey)) {
                continue;
            }
            long disabledUntil = DISABLED_UNTIL.getOrDefault(p.name, 0L);
            if (disabledUntil > System.currentTimeMillis()) {
                continue;
            }
            try {
                return OpenAICompatibleTranslator.translate(source, context, p.config);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (isDailyQuota(message)) {
                    DISABLED_UNTIL.put(p.name, System.currentTimeMillis() + DAILY_QUOTA_COOLDOWN_MS);
                } else if (isQuotaOrRateLimit(message)) {
                    DISABLED_UNTIL.put(p.name, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                }
                failures.add(p.name + ": " + message);
            }
        }

        StringBuilder out = new StringBuilder("Tất cả AI provider đã cấu hình đều thất bại.");
        if (!failures.isEmpty()) {
            out.append("\n\nChi tiết:");
            for (String failure : failures) out.append("\n• ").append(failure);
        }
        throw new IOException(out.toString());
    }

    public static boolean isQuotaOrRateLimit(String message) {
        if (message == null) return false;
        String s = message.toLowerCase(Locale.US);
        return s.contains("http 429")
                || s.contains("quota")
                || s.contains("rate limit")
                || s.contains("free-models-per-day")
                || s.contains("requests per day");
    }

    private static boolean isDailyQuota(String message) {
        if (message == null) return false;
        String s = message.toLowerCase(Locale.US);
        return s.contains("free-models-per-day")
                || s.contains("free model requests per day")
                || s.contains("requests per day")
                || s.contains("add 10 credits");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
