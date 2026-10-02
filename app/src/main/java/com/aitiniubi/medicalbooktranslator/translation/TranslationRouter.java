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
    public static final class QuotaException extends IOException {
        public QuotaException(String message) { super(message); }
    }

    public static final class Provider {
        public final String name;
        public final TranslationConfig config;
        public Provider(String name, TranslationConfig config) {
            this.name = name;
            this.config = config;
        }
    }

    private static final Map<String,Long> DISABLED_UNTIL = new ConcurrentHashMap<>();
    private static final ThreadLocal<TranslationLogger> ACTIVE_LOGGER = new ThreadLocal<>();
    private static final ThreadLocal<String> ACTIVE_STAGE = new ThreadLocal<>();

    public static void setDiagnostics(TranslationLogger logger, String stage) { ACTIVE_LOGGER.set(logger); ACTIVE_STAGE.set(stage); }
    public static void setStage(String stage) { ACTIVE_STAGE.set(stage); }
    public static void clearDiagnostics() { ACTIVE_LOGGER.remove(); ACTIVE_STAGE.remove(); }
    private static final long DAILY_QUOTA_COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    private static final long RATE_LIMIT_COOLDOWN_MS = 60L * 1000L;
    private static final long TEMPORARY_QUOTA_COOLDOWN_MS = 15L * 60L * 1000L;

    private TranslationRouter() {}

    public static String translate(String source, String context, List<Provider> providers) throws Exception { return translate(source, context, providers, ACTIVE_LOGGER.get(), ACTIVE_STAGE.get()==null?"AI_CALL":ACTIVE_STAGE.get()); }

    public static String translate(String source, String context, List<Provider> providers, TranslationLogger logger, String stage) throws Exception {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("Chưa cấu hình AI provider nào có API key.");
        }

        List<String> failures = new ArrayList<>();
        boolean sawQuotaDisabledProvider = false;
        for (Provider p : providers) {
            if (p == null || p.config == null || isBlank(p.config.endpoint) || isBlank(p.config.model) || isBlank(p.config.apiKey)) {
                continue;
            }
            long now = System.currentTimeMillis();
            long disabledUntil = DISABLED_UNTIL.getOrDefault(p.name, 0L);
            if (disabledUntil > now) {
                // Providers are disabled here only after a quota/rate-limit response.
                // Treating this as a generic "all providers failed" error could make
                // the job continue or retry unnecessarily on the next worker.
                sawQuotaDisabledProvider = true;
                if (logger != null) {
                    logger.event(stage, "PROVIDER_SKIPPED_QUOTA name=" + p.name
                            + " model=" + p.config.model
                            + " remainingMs=" + (disabledUntil-now));
                }
                continue;
            }
            try {
                String result = OpenAICompatibleTranslator.translate(source, context, p.config);
                if (logger != null) {
                    logger.event(stage, "PROVIDER_OK name=" + p.name + " model=" + p.config.model);
                }
                return result;
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (isQuotaOrRateLimit(message)) {
                    long cooldown = isDailyQuota(message)
                            ? DAILY_QUOTA_COOLDOWN_MS
                            : (isTemporaryQuota(message) ? TEMPORARY_QUOTA_COOLDOWN_MS : RATE_LIMIT_COOLDOWN_MS);
                    DISABLED_UNTIL.put(p.name, System.currentTimeMillis() + cooldown);
                    if (logger != null) {
                        logger.event(stage, "QUOTA_DETECTED name=" + p.name
                                + " model=" + p.config.model
                                + " cooldownMs=" + cooldown
                                + " reason=" + message);
                    }
                    // A quota/rate-limit error is a hard pause condition for the
                    // current translation job. Do NOT fail over to another provider:
                    // otherwise the app keeps consuming quota and the user loses
                    // the explicit PAUSED state requested for this workflow.
                    throw new QuotaException(message);
                }
                if (logger != null) logger.event(stage, "PROVIDER_FAIL name=" + p.name + " model=" + p.config.model + " reason=" + message);
                failures.add(p.name + ": " + message);
            }
        }

        if (sawQuotaDisabledProvider && failures.isEmpty()) {
            throw new QuotaException("AI provider đang bị tạm khóa do quota/rate limit; tạm dừng để tránh phát sinh thêm request.");
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
                || s.contains("code 429")
                || s.contains("resource_exhausted")
                || s.contains("quota")
                || s.contains("rate limit")
                || s.contains("free-models-per-day")
                || s.contains("requests per day")
                || s.contains("too many requests")
                || s.contains("generate_content_free_tier_requests");
    }

    private static boolean isDailyQuota(String message) {
        if (message == null) return false;
        String s = message.toLowerCase(Locale.US);
        return s.contains("free-models-per-day")
                || s.contains("free model requests per day")
                || s.contains("requests per day")
                || s.contains("add 10 credits");
    }

    private static boolean isTemporaryQuota(String message) {
        if (message == null) return false;
        String s = message.toLowerCase(Locale.US);
        return s.contains("requests per minute")
                || s.contains("tokens per minute")
                || s.contains("perminute")
                || s.contains("per_minute")
                || s.contains("rpm")
                || s.contains("tpm")
                || s.contains("retry-after")
                || s.contains("retryinfo")
                || s.contains("retry delay");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
