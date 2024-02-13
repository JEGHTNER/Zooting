package com.zooting.api.domain.meeting.entity;

import com.zooting.api.domain.BaseEntity;
import com.zooting.api.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

import lombok.*;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "meeting_log")
public class MeetingLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_room_id", columnDefinition = "BINARY(16)")
    private UUID uuid;

    @ManyToOne
    @JoinColumn(name = "email")
    private Member member;
}