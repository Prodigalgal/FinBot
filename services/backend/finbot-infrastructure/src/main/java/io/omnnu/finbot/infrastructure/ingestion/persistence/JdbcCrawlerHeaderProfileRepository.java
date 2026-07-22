package io.omnnu.finbot.infrastructure.ingestion.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.dto.CrawlerHeaderProfile;
import io.omnnu.finbot.application.ingestion.port.out.CrawlerHeaderProfileRepository;
import io.omnnu.finbot.application.ingestion.port.out.CrawlerHeaderProfileResolver;
import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcCrawlerHeaderProfileRepository
        implements CrawlerHeaderProfileRepository, CrawlerHeaderProfileResolver {
    private static final String PROFILE_COLUMNS = """
            profile.profile_id, profile.display_name, profile.user_agent,
            profile.accept_header, profile.accept_language,
            profile.additional_headers::text as additional_headers,
            profile.browser_template,
            profile.retain_sensitive_headers_on_cross_origin,
            profile.cross_origin_retain_headers::text as cross_origin_retain_headers,
            profile.captcha_bypass_enabled, profile.captcha_bypass_provider,
            profile.enabled, profile.version, profile.updated_at,
            (select count(*) from information_source source
              where source.crawler_header_profile_id = profile.profile_id
                and source.deleted_at is null) as usage_count
            """;
    private static final TypeReference<Map<String, String>> HEADER_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> HEADER_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcCrawlerHeaderProfileRepository(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<CrawlerHeaderProfile> listProfiles() {
        return jdbcClient.sql("select " + PROFILE_COLUMNS + " from crawler_header_profile profile"
                        + " where profile.deleted_at is null"
                        + " order by profile.display_name, profile.profile_id")
                .query(this::profile)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CrawlerHeaderProfile> findProfile(CrawlerHeaderProfileId profileId) {
        return jdbcClient.sql("select " + PROFILE_COLUMNS + " from crawler_header_profile profile"
                        + " where profile.profile_id = :profileId and profile.deleted_at is null")
                .param("profileId", profileId.value())
                .query(this::profile)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CrawlerHeaderProfile> resolve(SourceId sourceId) {
        return jdbcClient.sql("select " + PROFILE_COLUMNS + " from crawler_header_profile profile"
                        + " join information_source source"
                        + "   on source.crawler_header_profile_id = profile.profile_id"
                        + " where source.source_id = :sourceId"
                        + "   and source.deleted_at is null"
                        + "   and profile.deleted_at is null")
                .param("sourceId", sourceId.value())
                .query(this::profile)
                .optional();
    }

    @Override
    @Transactional
    public Optional<CrawlerHeaderProfile> createProfile(
            CrawlerHeaderProfile candidate,
            Instant createdAt) {
        var changed = jdbcClient.sql("""
                insert into crawler_header_profile (
                  profile_id, display_name, user_agent, accept_header, accept_language,
                  additional_headers, browser_template, retain_sensitive_headers_on_cross_origin,
                  cross_origin_retain_headers, captcha_bypass_enabled, captcha_bypass_provider,
                  enabled, version, created_at, updated_at
                ) values (
                  :profileId, :displayName, :userAgent, :acceptHeader, :acceptLanguage,
                  cast(:additionalHeaders as jsonb), :browserTemplate, :retainSensitive,
                  cast(:crossOriginRetain as jsonb), :captchaBypassEnabled, :captchaBypassProvider,
                  :enabled, 0, :createdAt, :createdAt
                ) on conflict (profile_id) do nothing
                """)
                .param("profileId", candidate.profileId().value())
                .param("displayName", candidate.displayName())
                .param("userAgent", candidate.userAgent())
                .param("acceptHeader", candidate.accept())
                .param("acceptLanguage", candidate.acceptLanguage())
                .param("additionalHeaders", json(candidate.additionalHeaders()))
                .param("browserTemplate", candidate.browserTemplate().name())
                .param("retainSensitive", candidate.retainSensitiveHeadersOnCrossOriginRedirect())
                .param("crossOriginRetain", json(List.copyOf(candidate.crossOriginRetainHeaders())))
                .param("captchaBypassEnabled", candidate.captchaBypassEnabled())
                .param("captchaBypassProvider", candidate.captchaBypassProvider().name())
                .param("enabled", candidate.enabled())
                .param("createdAt", timestamp(createdAt))
                .update();
        return changed == 1 ? findProfile(candidate.profileId()) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<CrawlerHeaderProfile> updateProfile(
            CrawlerHeaderProfile candidate,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update crawler_header_profile
                   set display_name = :displayName,
                       user_agent = :userAgent,
                       accept_header = :acceptHeader,
                       accept_language = :acceptLanguage,
                       additional_headers = cast(:additionalHeaders as jsonb),
                       browser_template = :browserTemplate,
                       retain_sensitive_headers_on_cross_origin = :retainSensitive,
                       cross_origin_retain_headers = cast(:crossOriginRetain as jsonb),
                       captcha_bypass_enabled = :captchaBypassEnabled,
                       captcha_bypass_provider = :captchaBypassProvider,
                       enabled = :enabled,
                       version = version + 1,
                       updated_at = :updatedAt
                 where profile_id = :profileId
                   and version = :expectedVersion
                   and deleted_at is null
                   and (:enabled = true or (profile_id <> 'header_default' and not exists (
                     select 1 from information_source source
                      where source.crawler_header_profile_id = crawler_header_profile.profile_id
                        and source.deleted_at is null
                   )))
                """)
                .param("profileId", candidate.profileId().value())
                .param("displayName", candidate.displayName())
                .param("userAgent", candidate.userAgent())
                .param("acceptHeader", candidate.accept())
                .param("acceptLanguage", candidate.acceptLanguage())
                .param("additionalHeaders", json(candidate.additionalHeaders()))
                .param("browserTemplate", candidate.browserTemplate().name())
                .param("retainSensitive", candidate.retainSensitiveHeadersOnCrossOriginRedirect())
                .param("crossOriginRetain", json(List.copyOf(candidate.crossOriginRetainHeaders())))
                .param("captchaBypassEnabled", candidate.captchaBypassEnabled())
                .param("captchaBypassProvider", candidate.captchaBypassProvider().name())
                .param("enabled", candidate.enabled())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? findProfile(candidate.profileId()) : Optional.empty();
    }

    @Override
    @Transactional
    public boolean archiveProfile(
            CrawlerHeaderProfileId profileId,
            long expectedVersion,
            Instant archivedAt) {
        return jdbcClient.sql("""
                update crawler_header_profile
                   set enabled = false, deleted_at = :archivedAt,
                       version = version + 1, updated_at = :archivedAt
                 where profile_id = :profileId
                   and version = :expectedVersion
                   and deleted_at is null
                   and profile_id <> 'header_default'
                   and not exists (
                     select 1 from information_source source
                      where source.crawler_header_profile_id = crawler_header_profile.profile_id
                        and source.deleted_at is null
                   )
                """)
                .param("profileId", profileId.value())
                .param("expectedVersion", expectedVersion)
                .param("archivedAt", timestamp(archivedAt))
                .update() == 1;
    }

    private CrawlerHeaderProfile profile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CrawlerHeaderProfile(
                new CrawlerHeaderProfileId(resultSet.getString("profile_id")),
                resultSet.getString("display_name"),
                resultSet.getString("user_agent"),
                resultSet.getString("accept_header"),
                resultSet.getString("accept_language"),
                headers(resultSet.getString("additional_headers")),
                CrawlerBrowserTemplate.from(resultSet.getString("browser_template")),
                resultSet.getBoolean("retain_sensitive_headers_on_cross_origin"),
                retainHeaders(resultSet.getString("cross_origin_retain_headers")),
                resultSet.getBoolean("captcha_bypass_enabled"),
                CrawlerCaptchaBypassProvider.from(resultSet.getString("captcha_bypass_provider")),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("usage_count"),
                resultSet.getLong("version"),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private Map<String, String> headers(String value) {
        try {
            return objectMapper.readValue(value, HEADER_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode crawler header profile", exception);
        }
    }

    private Set<String> retainHeaders(String value) {
        try {
            var list = objectMapper.readValue(value, HEADER_LIST);
            return Set.copyOf(new LinkedHashSet<>(list));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode cross-origin retain headers", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode crawler header profile", exception);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
