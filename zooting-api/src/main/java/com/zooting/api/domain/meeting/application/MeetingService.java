package com.zooting.api.domain.meeting.application;

import com.google.gson.Gson;
import com.zooting.api.domain.meeting.dao.WaitingRoomRedisRepository;
import com.zooting.api.domain.meeting.dto.FriendMeetingDto;
import com.zooting.api.domain.meeting.dto.MeetingMemberDto;
import com.zooting.api.domain.meeting.dto.MeetingSelectDto;
import com.zooting.api.domain.meeting.pubsub.MessageType;
import com.zooting.api.domain.meeting.pubsub.OpenviduTokenRes;
import com.zooting.api.domain.meeting.pubsub.RedisPublisher;
import com.zooting.api.domain.meeting.pubsub.WaitingRoomSubscriber;
import com.zooting.api.domain.member.dao.MemberRepository;
import com.zooting.api.domain.member.entity.Member;
import com.zooting.api.global.common.code.ErrorCode;
import com.zooting.api.global.exception.BaseExceptionHandler;
import io.openvidu.java.client.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class MeetingService {
    private final MemberRepository memberRepository;
    private final WaitingRoomRedisRepository waitingRoomRedisRepository;
    private final RedisMessageListenerContainer redisMessageListener;
    private final RedisPublisher redisPublisher;
    private final WaitingRoomSubscriber waitingRoomSubscriber;
    private final OpenVidu openVidu;
    private final SimpMessageSendingOperations webSocketTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Gson gson;

    /**
     * TODO: synchronized VS Lettuce의 Spin Lock VS Redisson의 분산락
     *   Mutex / Semaphore / Monitoring (Java의 synchronized 구현 방식)
     *
     * @param userDetails 대기열에 등록하려는 유저의 정보
     * @return 유저가 등록한 대기실의 ID
     */
    public synchronized String registerToWaitingRoom(UserDetails userDetails) {
        Member member = loadMemberFromDatabase(userDetails);
        MeetingMemberDto meetingMemberDto = member.toMeetingMemberDto();

        Iterable<WaitingRoom> waitingRooms = waitingRoomRedisRepository.findAll();
        WaitingRoom idealWaitingRoom = findIdealWaitingRoom(waitingRooms, meetingMemberDto);

        return registerMemberToWaitingRoom(idealWaitingRoom, meetingMemberDto);
    }

    public synchronized void exitFromWaitingRoom(UserDetails userDetails, String waitingRoomId) {
        Member member = loadMemberFromDatabase(userDetails);
        MeetingMemberDto meetingMemberDto = member.toMeetingMemberDto();

        WaitingRoom waitingRoom = loadWaitingRoomFromRedis(waitingRoomId);
        Set<MeetingMemberDto> waitingRoomMembers = waitingRoom.getMeetingMembers();

        waitingRoomMembers.remove(meetingMemberDto);

        if (waitingRoomMembers.isEmpty()) {
            waitingRoomRedisRepository.deleteById(waitingRoomId);
        } else { // 아니면 갱신함
            waitingRoomRedisRepository.save(waitingRoom);
        }
    }

    public synchronized void acceptMatching(String waitingRoomId) {
        WaitingRoom waitingRoom = loadWaitingRoomFromRedis(waitingRoomId);

        waitingRoom.setAcceptCount(waitingRoom.getAcceptCount() + 1);
        waitingRoomRedisRepository.save(waitingRoom);

        acceptMatchingMessagePublisher(waitingRoom);
    }

    /**
     * TODO: meetingMemberDto를 파라미터로 받아 최적의 대기실을 찾는 알고리즘 작성할 것
     *  들어가려는 유저와 동일한 성별의 유저가 2명 이상일 경우 들어가지 못하게 할 것
     *  현재는 대기실에 4명 이하면 무조건 들어가는 상태
     *
     * @param meetingMemberDto 대기열에 등록하려는 유저의 정보
     * @return 현재 유저가 들어갈수 있는 가장 이상적인 방
     */
    private WaitingRoom findIdealWaitingRoom(Iterable<WaitingRoom> waitingRooms, MeetingMemberDto meetingMemberDto) {
        // 알고리즘 로직 구현
        if (waitingRooms.iterator().hasNext()) {
            log.info("유저가 입장 가능한 대기열이 있습니다.");
            for (WaitingRoom waitingRoom : waitingRooms) {
                Set<MeetingMemberDto> meetingMembers = waitingRoom.getMeetingMembers();
                if (meetingMembers.size() < 2) {
                    return waitingRoom;
                }
            }
        }
        // 들어갈수 있는 방이 없는 경우 즉, waitingRooms가 비어있거나 상기 if문에서 return되지 않은 경우
        return createWaitingRoom();
    }

    private WaitingRoom createWaitingRoom() {
        String randomUUID = UUID.randomUUID().toString();
        WaitingRoom waitingRoom = WaitingRoom.builder()
                .waitingRoomId(randomUUID)
                .meetingMembers(new HashSet<>())
                .createdTime(LocalDateTime.now())
                .acceptCount(0)
                .expirationSeconds(-1L)
                .build();

        ChannelTopic channel = new ChannelTopic(MessageType.REDIS_HASH.getPrefix() + randomUUID);
        redisMessageListener.addMessageListener(waitingRoomSubscriber, channel);

        return waitingRoomRedisRepository.save(waitingRoom);
    }

    /**
     * 유저를 대기실에 등록하고 등록 message publish
     *
     * @param waitingRoom      유저를 등록할 대기실
     * @param meetingMemberDto 대기실에 등록하려는 유저의 정보
     * @return 유저가 등록한 대기실의 ID
     */
    private String registerMemberToWaitingRoom(WaitingRoom waitingRoom, MeetingMemberDto meetingMemberDto) {
        Set<MeetingMemberDto> waitingRoomMembers = Optional.ofNullable(waitingRoom.getMeetingMembers()).orElseThrow(
                () -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_WAITING_ROOM)
        );

        log.info("유저가 입장할 대기방의 정보를 가져옵니다: {}", waitingRoomMembers.toString());

        waitingRoomMembers.add(meetingMemberDto);
        waitingRoomRedisRepository.save(waitingRoom);

        waitingRoomRegistryMessagePublisher(waitingRoom);

        return waitingRoom.getWaitingRoomId();
    }

    /**
     * 유저의 대기실 입장 정보를 Publish Subscriber에 현재 대기실 인원 수를 전달
     *
     * @param waitingRoom 현재 대기실
     */
    private void waitingRoomRegistryMessagePublisher(WaitingRoom waitingRoom) {
        redisPublisher.publish(MessageType.REDIS_HASH.getPrefix() + waitingRoom.getWaitingRoomId(),
                MessageType.REGISTER.getPrefix() + waitingRoom.getMeetingMembers().size());
    }

    /**
     * 대기방에 있는 유저들의 매칭 수락 정보를 Publish Subscriber에 현재 매칭을 수락한 멤버들의 정보를 전달
     *
     * @param waitingRoom 현재 대기실
     */
    private void acceptMatchingMessagePublisher(WaitingRoom waitingRoom) {
        redisPublisher.publish(MessageType.REDIS_HASH.getPrefix() + waitingRoom.getWaitingRoomId(),
                MessageType.ACCEPTANCE.getPrefix() + waitingRoom.getAcceptCount());
    }

    private Member loadMemberFromDatabase(UserDetails userDetails) {
        Optional<Member> member = memberRepository.findMemberByEmail(userDetails.getUsername());
        return member.orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
    }

    private WaitingRoom loadWaitingRoomFromRedis(String waitingRoomId) {
        Optional<WaitingRoom> waitingRoom = waitingRoomRedisRepository.findById(waitingRoomId);
        return waitingRoom.orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_WAITING_ROOM));
    }

    /* 1대1 미팅 신청 */
    public void requestMeeting(String nickname, String loginEmail) {
        Member loginMember = memberRepository.findMemberByEmail(loginEmail).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        Member friend = memberRepository.findMemberByNickname(nickname).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        sendAcceptMessageToClient(friend, loginMember);
    }

    private void sendAcceptMessageToClient(Member friend, Member loginMember) {
        FriendMeetingDto friendMeetingDto = new FriendMeetingDto("meeting", loginMember.getEmail(), loginMember.getNickname());
        log.info("[sendAcceptMessageToClient] email: {} {} {} {}", friend.getEmail(), friendMeetingDto.type(), loginMember.getEmail(), loginMember.getNickname());
        webSocketTemplate.convertAndSend("/api/sub/dm/" + friend.getEmail(), friendMeetingDto);
    }

    /* 1대1 미팅 수락 */
    public void sendOpenViduTokenToClient(String nickname, String loginEmail) {
        Member friend = memberRepository.findMemberByNickname(nickname).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        try {
            Session session = openVidu.createSession();
            Connection connection = session.createConnection();
            OpenviduTokenRes openviduTokenRes = new OpenviduTokenRes("openviduToken", connection.getToken());
            webSocketTemplate.convertAndSend("/api/sub/dm/" + loginEmail, openviduTokenRes);
            connection = session.createConnection();
            openviduTokenRes = new OpenviduTokenRes("openviduToken", connection.getToken());
            webSocketTemplate.convertAndSend("/api/sub/dm/" + friend.getEmail(), openviduTokenRes);
        } catch (OpenViduJavaClientException | OpenViduHttpException ex) {
            throw new RuntimeException(ex);
        }
    }

    /* 화상채팅 종료 시 사람 선택 */
    public void selectPerson(String nickname, String loginEmail) {
        Member loginMember = memberRepository.findMemberByEmail(loginEmail).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        Member friend = memberRepository.findMemberByNickname(nickname).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        MeetingSelectDto meetingSelectDto = new MeetingSelectDto("select", loginMember.getNickname(), friend.getNickname());
        webSocketTemplate.convertAndSend("/api/sub/dm/" + friend.getEmail(), meetingSelectDto);
    }

    public void selectsPerson(String sessionId, String nickname, String loginEmail) {
        Member loginMember = memberRepository.findMemberByEmail(loginEmail).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        Member friend = memberRepository.findMemberByNickname(nickname).orElseThrow(() -> new BaseExceptionHandler(ErrorCode.NOT_FOUND_USER));
        MeetingSelectDto meetingSelectDto = new MeetingSelectDto("select", loginMember.getNickname(), friend.getNickname());
        redisTemplate.opsForList().rightPush(sessionId, gson.toJson(meetingSelectDto));
        redisTemplate.expire(sessionId, 180L, java.util.concurrent.TimeUnit.SECONDS);
    }

    public List<MeetingSelectDto> showResult(String sessionId) {
        List<Object> objectList = redisTemplate.opsForList().range(sessionId, 0, -1);
        if (objectList != null && !objectList.isEmpty()) {
            List<MeetingSelectDto> meetingSelectDtoList = objectList.stream()
                    .map(obj -> gson.fromJson((String) obj, MeetingSelectDto.class))
                    .collect(Collectors.toList());
            redisTemplate.expire(sessionId, 180L, java.util.concurrent.TimeUnit.SECONDS);
            return meetingSelectDtoList;
        }
        return Collections.emptyList();
    }
}