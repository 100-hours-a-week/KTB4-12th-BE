package com.gift.gift.infrastructure.google;

import java.nio.charset.StandardCharsets;
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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
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
    private final RestClient restClient;
    private CachedToken cachedToken;

    public GoogleServiceAccountTokenProvider(
            BugReportGoogleSheetsProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("googleApiRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public synchronized String getAccessToken() {
        Instant now = Instant.now();
        if (cachedToken != null
                && cachedToken.expiresAt().isAfter(now.plusSeconds(EXPIRY_SKEW_SECONDS))) {
            return cachedToken.value();
        }

        ServiceAccountCredentials credentials = readCredentials();
        String assertion = createAssertion(credentials, now);
        TokenResponse response = requestToken(credentials.tokenUri(), assertion);
        if (response == null || response.accessToken() == null) {
            throw deliveryFailed("Google OAuth 응답에 access_token이 없습니다.");
        }

        long expiresIn = response.expiresIn() > 0
                ? response.expiresIn()
                : TOKEN_LIFETIME_SECONDS;
        cachedToken = new CachedToken(
                response.accessToken(),
                now.plusSeconds(expiresIn)
        );
        return cachedToken.value();
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
            if (credentials.clientEmail() == null
                    || credentials.privateKey() == null) {
                throw deliveryFailed("Google 서비스 계정 정보가 올바르지 않습니다.");
            }
            return credentials;
        } catch (IllegalArgumentException | JacksonException exception) {
            throw deliveryFailed("Google 서비스 계정 정보를 읽을 수 없습니다.");
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
            throw deliveryFailed("Google OAuth 서명을 생성할 수 없습니다.");
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

    private TokenResponse requestToken(String tokenUri, String assertion) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        body.add("assertion", assertion);

        try {
            return restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException exception) {
            throw deliveryFailed("Google OAuth 토큰 발급에 실패했습니다.");
        }
    }

    private BugReportException deliveryFailed(String message) {
        return new BugReportException(
                BugReportErrorCode.DELIVERY_FAILED,
                message
        );
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServiceAccountCredentials(
            @JsonProperty("client_email") String clientEmail,
            @JsonProperty("private_key") String privateKey,
            @JsonProperty("token_uri") String configuredTokenUri
    ) {

        private String tokenUri() {
            return configuredTokenUri == null || configuredTokenUri.isBlank()
                    ? DEFAULT_TOKEN_URI
                    : configuredTokenUri;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }
}
