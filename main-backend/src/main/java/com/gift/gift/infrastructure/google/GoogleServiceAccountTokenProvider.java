package com.gift.gift.infrastructure.google;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;

@Component
public class GoogleServiceAccountTokenProvider {

    private static final String DEFAULT_TOKEN_URI =
            "https://oauth2.googleapis.com/token";
    private static final String SCOPE =
            "https://www.googleapis.com/auth/spreadsheets";
    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final BugReportGoogleSheetsProperties properties;
    private final ObjectMapper objectMapper;
    private final GoogleApiClient client;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private CachedToken cachedToken;

    public GoogleServiceAccountTokenProvider(
            BugReportGoogleSheetsProperties properties,
            ObjectMapper objectMapper,
            GoogleApiClient client
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    public String getAccessToken(long deadline) {
        boolean locked = false;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !tokenLock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                throw new GoogleApiFailure(false, 0, false, "Timeout");
            }
            locked = true;
            Instant now = Instant.now();
            if (cachedToken != null && cachedToken.expiresAt().isAfter(now.plusSeconds(EXPIRY_SKEW_SECONDS))) {
                return cachedToken.value();
            }
            ServiceAccountCredentials credentials = readCredentials();
            String assertion = createAssertion(credentials, now);
            var response = client.request(HttpRequest.newBuilder(URI.create(credentials.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + URLEncoder.encode(
                    "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8))), deadline);
            String token = response.path("access_token").asText("");
            if (!response.path("access_token").isString() || token.isBlank()
                || !response.path("expires_in").isIntegralNumber() || response.path("expires_in").asLong() <= 0) {
                throw new GoogleApiFailure(false, 0, false, "InvalidTokenResponse");
            }
            cachedToken = new CachedToken(token, now.plusSeconds(response.path("expires_in").asLong()));
            return token;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GoogleApiFailure(false, 0, false, "Interrupted");
        } finally {
            if (locked) tokenLock.unlock();
        }
    }

    public String projectScope() {
        return readCredentials().projectId();
    }

    public String accountScope() {
        var credentials = readCredentials();
        return credentials.projectId() + ":" + credentials.clientEmail();
    }

    private ServiceAccountCredentials readCredentials() {
        try {
            byte[] json = Base64.getDecoder().decode(
                    properties.credentialsJsonBase64()
            );
            ServiceAccountCredentials credentials = objectMapper.readValue(
                    json,
                    ServiceAccountCredentials.class
            );
            if (credentials == null || credentials.clientEmail() == null || credentials.clientEmail().isBlank()
                    || credentials.projectId() == null || credentials.projectId().isBlank()
                    || credentials.privateKey() == null || credentials.privateKey().isBlank()) {
                throw invalidCredentials();
            }
            return credentials;
        } catch (IllegalArgumentException | JacksonException exception) {
            throw invalidCredentials();
        }
    }

    private String createAssertion(
            ServiceAccountCredentials credentials,
            Instant now
    ) {
        try {
            String header = encodeJson(Map.of(
                    "alg", "RS256",
                    "typ", "JWT"
            ));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", credentials.clientEmail());
            claims.put("scope", SCOPE);
            claims.put("aud", credentials.tokenUri());
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plusSeconds(TOKEN_LIFETIME_SECONDS).getEpochSecond());
            String unsignedToken = header + "." + encodeJson(claims);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(readPrivateKey(credentials.privateKey()));
            signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));

            return unsignedToken + "." + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(signature.sign());
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException exception) {
            throw invalidCredentials();
        }
    }

    private String encodeJson(Object value) throws JacksonException {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private PrivateKey readPrivateKey(String pem) throws GeneralSecurityException {
        String encodedKey = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private GoogleApiFailure invalidCredentials() {
        return new GoogleApiFailure(true, 0, false, "InvalidCredentials");
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServiceAccountCredentials(
            @JsonProperty("client_email") String clientEmail,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("private_key") String privateKey,
            @JsonProperty("token_uri") String configuredTokenUri
    ) {

        private String tokenUri() {
            return configuredTokenUri == null || configuredTokenUri.isBlank()
                    ? DEFAULT_TOKEN_URI
                    : configuredTokenUri;
        }
    }

}
