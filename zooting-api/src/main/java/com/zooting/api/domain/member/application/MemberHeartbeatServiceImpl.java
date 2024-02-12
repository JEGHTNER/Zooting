package com.zooting.api.domain.member.application;

import com.google.gson.Gson;
import com.zooting.api.domain.friend.dao.FriendRepository;
import com.zooting.api.domain.member.dto.request.HeartBeatReq;
import com.zooting.api.domain.member.dto.response.AccessMemberStatus;
import com.zooting.api.domain.member.dto.response.HeartBeatRes;
import com.zooting.api.global.common.SocketBaseDtoRes;
import com.zooting.api.global.common.SocketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class MemberHeartbeatServiceImpl implements MemberHeartbeatService {
    private static final SocketType SOCKET_TYPE = SocketType.HEARTBEAT;
    private static final String HEARTBEAT_HASH = "heartbeat:";
    private static final Long TIME_TO_LIVE = 120L;
    private final FriendRepository friendRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Transactional(readOnly = true)
    public SocketBaseDtoRes<HeartBeatRes> loadOnlineFriends(HeartBeatReq heartBeatReq) {
        var online = redisTemplate.getExpire(HEARTBEAT_HASH + heartBeatReq.nickname(), TimeUnit.SECONDS);

        Set<String> onlineFriends;
        // 처음 접속하는 경우
        if (Objects.isNull(online) || online < TIME_TO_LIVE) {
            onlineFriends = checkFriendOnline(heartBeatReq);
        } // 저장되어 있다면 저장된 데이터 호출하고 expire 증가
        else {
            onlineFriends = getOnlineFriends(heartBeatReq);
        }
        redisTemplate.expire(HEARTBEAT_HASH + heartBeatReq.nickname(), TIME_TO_LIVE * 3, TimeUnit.SECONDS);
        return new SocketBaseDtoRes<>(SOCKET_TYPE, new HeartBeatRes(onlineFriends.stream().toList()));
    }

    private Set<String> getOnlineFriends(HeartBeatReq heartBeatReq) {
        var result = redisTemplate.opsForSet().members(HEARTBEAT_HASH + heartBeatReq.nickname());
        if (Objects.isNull(result)) return Set.of();
        return result.stream().map(Object::toString).collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Set<String> checkFriendOnline(HeartBeatReq heartBeatReq) {
        Set<String> onlineFriends = new HashSet<>();
        var friends = friendRepository.findFriendByFollower(heartBeatReq.memberId());
        for (var friend : friends) {
            var check = redisTemplate.getExpire(HEARTBEAT_HASH + friend.getFollowing().getNickname());
            // 친구가 접속해 있는 경우 내가 접속해 있다는 것도 알린다.
            if (Objects.nonNull(check) && check >= TIME_TO_LIVE) {
                onlineFriends.add(friend.getFollowing().getNickname());
                redisTemplate.opsForSet().add(HEARTBEAT_HASH + friend.getFollowing().getNickname(), heartBeatReq.nickname());
                redisTemplate.opsForSet().add(HEARTBEAT_HASH + heartBeatReq.nickname(), friend.getFollowing().getNickname());
            }
        }
        if (onlineFriends.isEmpty()) {
            redisTemplate.opsForSet().add(HEARTBEAT_HASH + heartBeatReq.nickname(), heartBeatReq.nickname());
        }
        return onlineFriends;
    }

    public void updateMemberStatus() {
        // 접속 해제한 유저 정보 불러오기
        AccessMemberStatus accessMemberStatus = updateOfflineMemberStatus();
        log.info("online: {}, offline: {}", accessMemberStatus.onlineMembers().size(), accessMemberStatus.offlineMembers().size());
        // 친구 정보에서 삭제 처리하기
        updateOnlineMemberStatus(accessMemberStatus);
    }

    private void updateOnlineMemberStatus(AccessMemberStatus accessMemberStatus) {
        if (accessMemberStatus.offlineMembers().isEmpty()) return;

        for (var onlineMember : accessMemberStatus.onlineMembers()) {
            redisTemplate.opsForSet().remove(HEARTBEAT_HASH + onlineMember,
                    accessMemberStatus.offlineMembers().toArray(new String[0]));
        }
    }

    private AccessMemberStatus updateOfflineMemberStatus() {
        var hashIds = redisTemplate.keys(HEARTBEAT_HASH + "*"); // 저장된 멤버의 키 로드
        if (Objects.isNull(hashIds) || hashIds.isEmpty())
            return new AccessMemberStatus(List.of(), List.of());

        List<String> onlineMembers = new ArrayList<>();
        List<String> offlineMembers = new ArrayList<>();
        for (var hashId : hashIds) {
            var expireTime = redisTemplate.getExpire(hashId);
            var nickname = hashId.substring(HEARTBEAT_HASH.length());
            if (Objects.isNull(expireTime) || expireTime < TIME_TO_LIVE) {
                offlineMembers.add(nickname);
            } else {
                onlineMembers.add(nickname);
            }
        }
        // 오프라인 유저의 데이터 삭제
        redisTemplate.delete(offlineMembers.stream().map(s -> HEARTBEAT_HASH + s).toList());
        return new AccessMemberStatus(onlineMembers, offlineMembers);
    }
}
