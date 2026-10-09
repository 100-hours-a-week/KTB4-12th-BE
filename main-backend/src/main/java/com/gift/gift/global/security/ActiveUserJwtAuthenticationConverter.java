package com.gift.gift.global.security;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.user.service.AuthenticatedUserService;

@Component
@RequiredArgsConstructor
public class ActiveUserJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AuthenticatedUserService authenticatedUserService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());

        try {
            if (!authenticatedUserService.isActive(userId)) {
                throw new BadCredentialsException(
                        "서비스 이용이 불가능한 회원입니다."
                );
            }
        } catch (DataAccessException exception) {
            throw new InternalAuthenticationServiceException(
                    "사용자의 인증 정보를 불러올 수 없습니다.",
                    exception
            );
        }

        return new JwtAuthenticationToken(
                jwt,
                List.of(),      // 사용자의 권한 정보 리스트
                userId.toString()
        );
    }
}
