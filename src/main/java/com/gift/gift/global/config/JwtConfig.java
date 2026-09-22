package com.gift.gift.global.config;

import java.time.Clock;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.gift.gift.global.security.AccessTokenClaimsValidator;
import com.gift.gift.global.security.JwtProperties;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] keyBytes = Base64.getDecoder()
                .decode(properties.secretBase64());

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT 서명 키는 32바이트 이상이어야 합니다."
            );
        }

        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey jwtSigningKey,
            JwtProperties properties,
            Clock clock
    ) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtIssuerValidator(properties.issuer()),
                        new AccessTokenClaimsValidator(clock)
                )
        );

        return decoder;
    }
}
