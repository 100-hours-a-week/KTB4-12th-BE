package com.gift.gift.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "terms",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_terms_code_version",
                        columnNames = {"term_code", "version"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Term extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 50)
    @Column(name = "term_code", nullable = false, length = 50, updatable = false)
    private String termCode;

    @NotBlank
    @Size(max = 20)
    @Column(name = "version", nullable = false, length = 20, updatable = false)
    private String version;

    @NotBlank
    @Size(max = 200)
    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    @NotBlank
    @Column(name = "content", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String content;

    @Column(name = "is_required", nullable = false, updatable = false)
    private boolean isRequired;

    public Term(
            String termCode,
            String version,
            String title,
            String content,
            boolean isRequired
    ) {
        this.termCode = termCode;
        this.version = version;
        this.title = title;
        this.content = content;
        this.isRequired = isRequired;
    }
}
