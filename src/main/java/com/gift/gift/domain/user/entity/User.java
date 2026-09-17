package com.gift.gift.domain.user.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.user.support.EmailNormalizer;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_users_email",
                        columnNames = "email"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Email
    @Size(max = 254)
    @Column(name = "email", nullable = false, length = 255, updatable = false)
    private String email;

    @NotBlank
    @Pattern(
            regexp = "^\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}$"
    )
    @Column(name = "password", nullable = false, length = 255)
    private String passwordHash;

    @NotBlank
    @Size(max = 30)
    @Pattern(
            regexp = "^[가-힣A-Za-z](?:[가-힣A-Za-z ]*[가-힣A-Za-z])?$"
    )
    @Column(name = "name", nullable = false, length = 30)
    private String name;

    @NotNull
    @Column(name = "birth", nullable = false)
    private LocalDate birth;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 255)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "is_birthday_public", nullable = false)
    private boolean isBirthdayPublic = false;

    @Column(name = "is_first_login", nullable = false)
    private boolean isFirstLogin = true;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public User(
            String email,
            String passwordHash,
            String name,
            LocalDate birth
    ) {
        this.email = EmailNormalizer.normalize(email);
        this.passwordHash = passwordHash;
        this.name = name;
        this.birth = birth;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE && getDeletedAt() == null;
    }

    public boolean completeOnboarding() {
        if (!isFirstLogin) {
            return false;
        }

        this.isFirstLogin = false;
        return true;
    }
}
