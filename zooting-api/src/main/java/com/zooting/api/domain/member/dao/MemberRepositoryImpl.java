package com.zooting.api.domain.member.dao;


import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateTemplate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zooting.api.domain.member.entity.Member;
import com.zooting.api.domain.member.entity.Privilege;
import lombok.RequiredArgsConstructor;
import org.joda.time.DateTime;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static com.zooting.api.domain.member.entity.QMember.member;


@RequiredArgsConstructor
public class  MemberRepositoryImpl implements MemberRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<Member> extractMatchingMember(ExtractObj extractObj) {
        return queryFactory
                .selectFrom(member)
                .where(
                        // 차단 목록의 친구는 매칭되지 않는다
                        notInBlockToList(extractObj.getBlockToList()),
                        notInBlockFromList(extractObj.getBlockFromList()),
                        // 이미 친구라면 매칭이 되지 않는다
                        notInFriendList(extractObj.getFriendList()),
                        // 2~10살 차이 사람 조회
                        betweenRangeYear(extractObj.getMemberBirthYear(), extractObj.getRangeYear()),
                        // 멤버 role이 USER
                        member.role.contains(Privilege.USER)
                        // 다른 성별
//                        member.gender.ne(extractObj.getGender())
                ).orderBy(
                        // 관심사가 일치하는 유저가 먼저 오도록 sort
                        member.additionalInfo.interest.in(extractObj.getMemberInterests()).count().desc(),
                        // 이상형이 일치하는 유저가 먼저 오도록 sort
                        member.additionalInfo.animal.in(extractObj.getMemberIdeals()).count().desc()
                ).groupBy(member.additionalInfo.interest, member.additionalInfo.idealAnimal)
                .fetch();
    }

    private BooleanExpression notInBlockToList(List<String> blockToList) {
        if (blockToList != null || !blockToList.isEmpty()) {
            return member.email.notIn(blockToList);
        }
        return null;
    }

    private BooleanExpression notInBlockFromList(List<String> blockFromList) {
        if (blockFromList != null || !blockFromList.isEmpty()) {
            return member.email.notIn(blockFromList);
        }
        return null;
    }

    private BooleanExpression notInFriendList(List<String> friendList) {
        if (friendList != null || !friendList.isEmpty()) {
            return member.email.notIn(friendList);
        }
        return null;
    }

    private BooleanExpression betweenRangeYear(Integer memberBirthYear, Integer rangeYear) {
        int minYear = memberBirthYear - rangeYear;
        int maxYear = memberBirthYear + rangeYear;
        if (member.birth != null && rangeYear != null) {
            return member.birth.year().between(minYear, maxYear);
        }
        return null;
    }
}


