package com.zooting.api.domain.meeting.pubsub;

import com.zooting.api.domain.meeting.dao.MeetingLogRepository;
import com.zooting.api.domain.meeting.application.WaitingRoom;
import com.zooting.api.domain.meeting.dao.WaitingRoomRedisRepository;
import com.zooting.api.domain.meeting.dto.MeetingMemberDto;
import com.zooting.api.domain.meeting.dto.OppositeGenderParticipantsDto;
import com.zooting.api.domain.meeting.dto.response.OpenviduTokenRes;
import com.zooting.api.domain.meeting.dto.response.RedisMatchRes;
import com.zooting.api.domain.meeting.entity.MeetingLog;
import com.zooting.api.domain.member.dao.MemberRepository;
import com.zooting.api.global.common.SocketBaseDtoRes;
import com.zooting.api.global.common.SocketType;
import com.zooting.api.global.common.code.ErrorCode;
import com.zooting.api.global.exception.BaseExceptionHandler;
import io.openvidu.java.client.Connection;
import io.openvidu.java.client.OpenVidu;
import io.openvidu.java.client.OpenViduHttpException;
import io.openvidu.java.client.OpenViduJavaClientException;
import io.openvidu.java.client.Session;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;


@Log4j2
@Component
@RequiredArgsConstructor
public class WaitingRoomSubscriber implements MessageListener {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisMessageListenerContainer redisMessageListener;
    private final WaitingRoomRedisRepository waitingRoomRedisRepository;
    private final MeetingLogRepository meetingLogRepository;
    private final MemberRepository memberRepository;
    private final SimpMessageSendingOperations webSocketTemplate;
    private final OpenVidu openVidu;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        final int MEETING_CAPACITY = 4;
        final WaitingRoomMessageDto parsedMessage = waitingRoomMessageParser(message);
        final String waitingRoomId = parsedMessage.getId();
        final String type = parsedMessage.getType();
        final int count = parsedMessage.getCount();

        log.info("[onMessage] Type: {}, WaitingRoomId: {}, Count: {}", type, waitingRoomId, count);

        WaitingRoom waitingRoom = waitingRoomRedisRepository.findById(waitingRoomId)
                .orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_ERROR));

        if (MessageType.REGISTER.getPrefix().contains(type) && count == MEETING_CAPACITY) {
            log.info("[onMessage] 유저 {}명이 대기열 등록을 완료했습니다. ", MEETING_CAPACITY);
            sendAcceptMessageToClient(waitingRoom);

            // 매칭 완료되었울 경우 10초내로 수락 버튼 누르도록
            waitingRoom.setExpirationSeconds(10L);
            waitingRoomRedisRepository.save(waitingRoom);
        } else if (MessageType.ACCEPTANCE.getPrefix().contains(type) && count == MEETING_CAPACITY) {
            log.info("[onMessage] 유저 {}명이 수락 버튼을 눌렀습니다. ", MEETING_CAPACITY);
            sendOpenViduTokenToClient(waitingRoom);
        }
    }

    /**
     * 꽉 찬 대기실 유저들에게 매칭 수락 버튼 발송
     * @param waitingRoom 꽉 찬 대기실
     */

    private void sendAcceptMessageToClient(WaitingRoom waitingRoom) {
        log.info("[onMessage] key: {}, 매칭성공", waitingRoom.getWaitingRoomId());
        for (MeetingMemberDto meetingMemberDto : waitingRoom.getMeetingMembers()) {
            String email = meetingMemberDto.getEmail();
            RedisMatchRes redisMatchRes = new RedisMatchRes(waitingRoom.getWaitingRoomId());
            log.info("[onMessage] email: {} {}", email, redisMatchRes.roomId());
            webSocketTemplate.convertAndSend("/api/sub/" + email, new SocketBaseDtoRes<>(SocketType.MATCH, waitingRoom.getWaitingRoomId()));
        }
    }

    /**
     * 유저 전원이 수락버튼을 눌렀을 경우 토큰 발급 후 대기실 삭제
     * @param waitingRoom 매칭이 완료된 대기실
     */
    private void sendOpenViduTokenToClient(WaitingRoom waitingRoom) {
        try {
            Session session = openVidu.createSession();
            log.info("세션을 만들었습니다.");
            Map<String, String> nicknameGenderMap = waitingRoom.getMeetingMembers().stream()
                    .collect(Collectors.toMap(MeetingMemberDto::getEmail, MeetingMemberDto::getGender));

            for (MeetingMemberDto meetingMemberDto : waitingRoom.getMeetingMembers()) {
                String email = meetingMemberDto.getEmail();


                // 미팅 로그 저장
                //TODO: 메소드화 시킬 것 (1)
                meetingLogRepository.save(
                        MeetingLog
                        .builder()
                        .uuid(UUID.fromString(waitingRoom.getWaitingRoomId()))
                        .member(memberRepository.findMemberByEmail(email)
                                .orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER))).build());

                //다른 성별만 찾기
                //TODO: 메소드화 시킬 것 (2)
                List<OppositeGenderParticipantsDto> oppositeGenderParticipantsDtos = waitingRoom.getMeetingMembers().stream()
                        .filter(member -> !member.getGender().equals(nicknameGenderMap.get(email)))
                        .map(member -> new OppositeGenderParticipantsDto(member.getNickname(), member.getAnimal()))
                        .collect(Collectors.toList());

                Connection connection = session.createConnection();
                OpenviduTokenRes openviduTokenRes = new OpenviduTokenRes(connection.getToken(), oppositeGenderParticipantsDtos);
                webSocketTemplate.convertAndSend("/api/sub/" + email, new SocketBaseDtoRes<>(SocketType.OPENVIDU, openviduTokenRes));
            }
            waitingRoomRedisRepository.deleteById(waitingRoom.getWaitingRoomId());
            redisMessageListener.removeMessageListener(this, new ChannelTopic(waitingRoom.getWaitingRoomId()));
        } catch (OpenViduJavaClientException | OpenViduHttpException ex) {
            throw new RuntimeException(ex);
        }
    }

    private WaitingRoomMessageDto waitingRoomMessageParser(Message message) {
        String waitingRoomIdWithRedisHash = new String(message.getChannel());
        String waitingRoomId = waitingRoomIdWithRedisHash.split(":")[1];

        String[] typeAndCount = Objects.requireNonNull(
                redisTemplate.getStringSerializer().deserialize(message.getBody())).replaceAll("\"", "").split(" ");

        return WaitingRoomMessageDto.builder().id(waitingRoomId).type(typeAndCount[0])
                .count(Integer.parseInt(typeAndCount[1])).build();
    }
}