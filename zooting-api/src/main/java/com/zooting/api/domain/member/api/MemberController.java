package com.zooting.api.domain.member.api;

import com.zooting.api.domain.member.application.MemberService;
import com.zooting.api.domain.member.dto.request.*;
import com.zooting.api.domain.member.dto.response.*;
import com.zooting.api.domain.member.entity.Privilege;
import com.zooting.api.global.common.BaseResponse;
import com.zooting.api.global.common.code.ErrorCode;
import com.zooting.api.global.common.code.SuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.text.ParseException;
import java.util.List;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
@Tag(name = "멤버", description = "Member 관련 API")
public class MemberController {
    private final MemberService memberService;

    @PreAuthorize("permitAll()")
    @Operation(
            summary = "닉네임 중복 체크",
            description = "닉네임이 중복될 때 true반환, " +
                    "닉네임 중복되지 않을 때 false 반환"
    )
    @GetMapping("/nickname/check")
    public ResponseEntity<BaseResponse<Boolean>> checkNicknameDuplicate(
            @Valid @NotNull @Size(min = 2, max = 16) @RequestParam(name = "nickname") String nickname) {
        var result = memberService.existNickname(nickname);
        return BaseResponse.success(
                SuccessCode.CHECK_SUCCESS,
                result
        );
    }

    @PreAuthorize("permitAll()")
    @Operation(
            summary = "로그인 후 추가 정보(유저 동물상 정보 기준)가 저장 여부 확인",
            description = "previlege로 판단. " +
                    "false - 추가 정보 저장되지 않음" +
                    "true - 추가 정보 저장되어 있음")
    @GetMapping("/privilege/check")
    public ResponseEntity<BaseResponse<List<Privilege>>> checkPrivilege(@AuthenticationPrincipal UserDetails userDetails) {
        var result = memberService.checkMemberPrivilege(userDetails.getUsername());
        return BaseResponse.success(
                SuccessCode.CHECK_SUCCESS,
                result
        );
    }

    @PreAuthorize("hasAnyRole('ANONYMOUS', 'USER')")
    @Operation(
            summary = "프로필 확인",
            description = "내 프로필이 맞다면 myprofile = true" +
                    "내 프로필이 아니라면 myprofile = false"
    )
    @GetMapping("/myprofile/check")
    public ResponseEntity<BaseResponse<MyProfileReq>> checkMyProfile(
            @Valid @NotNull @Size(min = 2, max = 16) @RequestParam(name = "nickname") String nickname,
            @AuthenticationPrincipal UserDetails userDetails) {
        var result = memberService.checkMyProfile(userDetails.getUsername(), nickname);
        return BaseResponse.success(
                SuccessCode.CHECK_SUCCESS,
                result
        );
    }

    @Operation(summary = "로그인 후 추가 정보 저장")
    @PreAuthorize("hasAnyRole('ANONYMOUS')")
    @PutMapping
    public ResponseEntity<BaseResponse<String>> saveAdditionalInfo(
            @Valid @RequestBody MemberReq memberReq,
            @AuthenticationPrincipal UserDetails userDetails
    ) throws ParseException {
        memberService.updateMemberInfo(userDetails.getUsername(), memberReq);
        return BaseResponse.success(
                SuccessCode.UPDATE_SUCCESS,
                "추가 정보 저장 성공"
        );
    }

    @Operation(summary = "로그인 후 추가 정보 수정 (지역, 이상형)")
    @PreAuthorize("hasAnyRole('USER')")
    @PatchMapping("/info")
    public ResponseEntity<BaseResponse<String>> updateAdditionalInfo(
            @Valid @RequestBody MemberModifyReq memberModifyReq,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        memberService.updateMemberInfo(userDetails.getUsername(), memberModifyReq);
        return BaseResponse.success(
                SuccessCode.UPDATE_SUCCESS,
                "지역, 이상형 정보 업데이트 성공"
        );
    }

    @Operation(summary = "로그인한 유저 정보 조회")
    @PreAuthorize("hasAnyRole('ANONYMOUS', 'USER')")
    @GetMapping
    public ResponseEntity<BaseResponse<MemberRes>> findMemberInfo(
            @AuthenticationPrincipal UserDetails userDetails) {
        MemberRes memberRes = memberService.findMemberInfo(userDetails.getUsername());
        return BaseResponse.success(
                SuccessCode.SELECT_SUCCESS,
                memberRes
        );
    }

    @PreAuthorize("hasAnyRole('USER')")
    @Operation(summary = "차단 리스트 조회")
    @GetMapping("/blocklist")
    public ResponseEntity<BaseResponse<List<MemberSearchRes>>> findMyBlockList(
            @AuthenticationPrincipal UserDetails userDetails) {
        var result = memberService.findMyBlockList(userDetails.getUsername());

        return BaseResponse.success(
                SuccessCode.CHECK_SUCCESS,
                result
        );
    }

    @Operation(summary = "닉네임으로 유저 정보 조회")
    @PreAuthorize("hasAnyRole('USER')")
    @GetMapping("/info")
    public ResponseEntity<BaseResponse<MemberRes>> findMemberInfoByNickname(
            @Valid @NotNull @Size(min = 2, max = 16) @RequestParam(name = "nickname") String nickname,
            @AuthenticationPrincipal UserDetails userDetails) {
        MemberRes memberRes = memberService.findMemberInfoByNickname(userDetails.getUsername(), nickname);
        return BaseResponse.success(
                SuccessCode.SELECT_SUCCESS,
                memberRes
        );
    }

    @Operation(summary = "포인트 차감 후 닉네임 변경")
    @PreAuthorize("hasAnyRole('USER')")
    @PatchMapping("/nickname")
    public ResponseEntity<BaseResponse<String>> modifyNickname(
            @Valid @RequestBody NicknameReq nicknameReq,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (memberService.modifyNickname(userDetails.getUsername(), nicknameReq)) {
            return BaseResponse.success(
                    SuccessCode.UPDATE_SUCCESS,
                    "닉네임 변경 완료"
            );
        }
        return BaseResponse.error(
                ErrorCode.FAILED_TO_UPDATE_MEMBER,
                "닉네임 변경 실패 - 잔여 포인트 부족 / 닉네임 중복"
        );
    }

    @Operation(summary = "관심사, 이상형 수정")
    @PreAuthorize("hasAnyRole('USER')")
    @PatchMapping("/interests")
    public ResponseEntity<BaseResponse<String>> updateInterests(
            @Valid @RequestBody InterestsReq interestsReq,
            @AuthenticationPrincipal UserDetails userDetails) {
        memberService.updateInterests(userDetails.getUsername(), interestsReq);
        return BaseResponse.success(
                SuccessCode.UPDATE_SUCCESS,
                "관심사 수정 완료"
        );
    }

    @Operation(summary = "자기소개 수정")
    @PreAuthorize("hasAnyRole('USER')")
    @PatchMapping("/introduce")
    public ResponseEntity<BaseResponse<String>> updateIntroduce(
            @Valid @RequestBody IntroduceReq introduceReq,
            @AuthenticationPrincipal UserDetails userDetails) {
        memberService.updateIntroduce(userDetails.getUsername(), introduceReq);
        return BaseResponse.success(
                SuccessCode.UPDATE_SUCCESS,
                "자기소개 수정 완료"
        );
    }

    @Operation(
            summary = "멤버 검색",
            description = "검색한 키워드에 해당하는 멤버 중 나를 차단한 사람 제외하고 리스트로 반환"
    )
    @PreAuthorize("hasAnyRole('USER')")
    @GetMapping("/searchlist")
    public ResponseEntity<BaseResponse<MemberSearchPageRes>> findMemberList(
            @PageableDefault(sort="nickname", direction = Sort.Direction.DESC, page=0) Pageable pageable,
            @RequestParam(name = "nickname", required = false) String nickname,
            @AuthenticationPrincipal UserDetails userDetails) {
        MemberSearchPageRes memberResList = memberService.findMemberList(pageable, userDetails.getUsername(), nickname);
        return BaseResponse.success(
                SuccessCode.SELECT_SUCCESS,
                memberResList
        );
    }

    @Operation(summary = "성격 수정")
    @PreAuthorize("hasAnyRole('ANONYMOUS', 'USER')")
    @PatchMapping("/characters")
    public ResponseEntity<BaseResponse<String>> updatePersonality(
            @Valid @RequestBody PersonalityReq personalityReq,
            @AuthenticationPrincipal UserDetails userDetails) {
        memberService.updatePersonality(userDetails.getUsername(), personalityReq);
        return BaseResponse.success(
                SuccessCode.UPDATE_SUCCESS,
                "성격 수정 완료"
        );
    }


    @Operation(summary = "내 포인트 조회")
    @PreAuthorize("hasAnyRole('USER')")
    @GetMapping("/points")
    public ResponseEntity<BaseResponse<PointRes>> findPoints(
            @RequestParam(name = "nickname") String nickname,
            @AuthenticationPrincipal UserDetails userDetails) {
        PointRes result = memberService.findPoints(userDetails.getUsername());
        return BaseResponse.success(
                SuccessCode.SELECT_SUCCESS,
                result
        );
    }

    @Operation(
            summary = "내 마스크 변경",
            description = "유저의 동물상과 일치할때만 마스크 변경 가능"
    )
    @PreAuthorize("hasAnyRole('USER')")
    @PatchMapping("/mask")
    public ResponseEntity<BaseResponse<String>> changeMask(
            @RequestBody MaskReq maskReq,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (memberService.changeMask(userDetails.getUsername(), maskReq))
        {
            return BaseResponse.success(
                    SuccessCode.UPDATE_SUCCESS,
                    "마스크 변경 완료"
            );
        }
        return BaseResponse.error(
                ErrorCode.FAILED_TO_UPDATE_MEMBER,
                "동물상 변경 실패 - 유저의 동물상과 불일치 / 잘못된 마스크 id 접근"
        );

    }


    @Operation(summary = "내 배경이미지 변경")
    @PreAuthorize("hasAnyRole('USER')")
    @PatchMapping("/background")
    public ResponseEntity<BaseResponse<String>> changeBackground(
            @RequestBody BackgroundReq backgroundReq,
            @AuthenticationPrincipal UserDetails userDetails) {
        memberService.changeBackground(userDetails.getUsername(), backgroundReq);
        return BaseResponse.success(
                SuccessCode.UPDATE_SUCCESS,
                "배경 변경 완료"
        );
    }
    @Operation(
            summary = "매칭 인원 추출",
            description = "차단 목록 유저 제외" +
                    "친구인 유저 제외" +
                    "나이 선택 o 시 - 해당 나이차 유저 추출" +
                    "나이 선택 x 시 - 전체 나이대 유저 추출" +
                    "관심사 일치하는 유저 순 정렬" +
                    "이상형 일치하는 유저 순 정렬"
    )
    @PreAuthorize("hasAnyRole('USER')")
    @GetMapping("/extract")
    public ResponseEntity<BaseResponse<List<MemberSearchRes>>> extractMemberList(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ExtractingReq extractingReq) {
        List<MemberSearchRes> memberResList = memberService.extractMembers(userDetails.getUsername(), extractingReq);
        return BaseResponse.success(
                SuccessCode.SELECT_SUCCESS,
                memberResList
        );
    }
}
