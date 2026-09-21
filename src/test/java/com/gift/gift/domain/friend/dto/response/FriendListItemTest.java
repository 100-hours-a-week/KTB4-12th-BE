package com.gift.gift.domain.friend.dto.response;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.friend.repository.FriendQueryRow;

import static org.assertj.core.api.Assertions.assertThat;

class FriendListItemTest {

    @Test
    @DisplayName("조회 행의 식별자와 이름, 이메일을 그대로 응답 항목에 담는다")
    void from_mapsIdentityFields() {
        FriendQueryRow row = row(LocalDate.of(2000, 3, 14), true);

        FriendListItem item = FriendListItem.from(row);

        assertThat(item.friendId()).isEqualTo(31L);
        assertThat(item.userId()).isEqualTo(27L);
        assertThat(item.name()).isEqualTo("김민지");
        assertThat(item.email()).isEqualTo("minji@example.com");
    }

    @Test
    @DisplayName("생일을 공개한 친구는 출생 연도 없이 MM-dd 문자열로 반환한다")
    void from_returnsMonthAndDay_whenBirthdayIsPublic() {
        FriendListItem item = FriendListItem.from(row(LocalDate.of(2000, 3, 14), true));

        assertThat(item.birth()).isEqualTo("03-14");
    }

    @Test
    @DisplayName("월과 일이 한 자리여도 두 자리로 맞춰 반환한다")
    void from_padsMonthAndDay_whenValuesAreSingleDigit() {
        FriendListItem item = FriendListItem.from(row(LocalDate.of(2000, 1, 5), true));

        assertThat(item.birth()).isEqualTo("01-05");
    }

    @Test
    @DisplayName("생일을 공개하지 않은 친구의 생일은 null로 반환한다")
    void from_returnsNullBirth_whenBirthdayIsPrivate() {
        FriendListItem item = FriendListItem.from(row(LocalDate.of(2000, 3, 14), false));

        assertThat(item.birth()).isNull();
    }

    private FriendQueryRow row(LocalDate birth, boolean birthdayPublic) {
        return new FriendQueryRow(31L, 27L, "김민지", "minji@example.com", birth, birthdayPublic);
    }
}
