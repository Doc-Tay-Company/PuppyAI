package com.puppy.controller;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.FocusSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerEditIntentTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 20);

    private final List<FocusSession> sessions = List.of(
            new FocusSession("1", "Math", "Calculus review", FocusType.QUICK,
                    DATE, LocalTime.of(8, 0), 50, Priority.HIGH, false),
            new FocusSession("2", "Physics", "Mechanics", FocusType.DEEP,
                    DATE, LocalTime.of(10, 0), 60, Priority.MEDIUM, false)
    );

    @Test
    void detectsVietnameseEditKeywords() {
        assertTrue(ChatController.isPlanEditIntent("đổi Math sang 19:00", sessions));
        assertTrue(ChatController.isPlanEditIntent("chuyển buổi học lý sang ngày mai", sessions));
        assertTrue(ChatController.isPlanEditIntent("chỉnh sửa session 1", sessions));
        assertTrue(ChatController.isPlanEditIntent("sửa lịch học toán", sessions));
        assertTrue(ChatController.isPlanEditIntent("xóa buổi physics", sessions));
        assertTrue(ChatController.isPlanEditIntent("bỏ session 2", sessions));
        assertTrue(ChatController.isPlanEditIntent("hủy buổi thứ 4", sessions));
        assertTrue(ChatController.isPlanEditIntent("thêm 1 buổi chemistry 30 phút", sessions));
        assertTrue(ChatController.isPlanEditIntent("dời lịch học sang 20:00", sessions));
    }

    @Test
    void detectsEnglishEditKeywords() {
        assertTrue(ChatController.isPlanEditIntent("move Math to 19:00", sessions));
        assertTrue(ChatController.isPlanEditIntent("reschedule Physics session", sessions));
        assertTrue(ChatController.isPlanEditIntent("change the time of session 1", sessions));
        assertTrue(ChatController.isPlanEditIntent("delete session 2", sessions));
        assertTrue(ChatController.isPlanEditIntent("remove the math session", sessions));
        assertTrue(ChatController.isPlanEditIntent("add a Chemistry review session", sessions));
        assertTrue(ChatController.isPlanEditIntent("adjust session 1 duration to 30 minutes", sessions));
    }

    @Test
    void detectsSubjectWithTimeAsEdit() {
        // "Math 19h" or "Physics 20:00"
        assertTrue(ChatController.isPlanEditIntent("Math 19h", sessions));
        assertTrue(ChatController.isPlanEditIntent("Physics 20:00", sessions));
    }

    @Test
    void detectsHorizonIntent() {
        assertTrue(ChatController.isPlanHorizonIntent("kéo dài kế hoạch đến ngày 25"));
        assertTrue(ChatController.isPlanHorizonIntent("rút ngắn kế hoạch xuống 3 ngày"));
        assertTrue(ChatController.isPlanHorizonIntent("kéo dài thêm 2 ngày"));
        assertTrue(ChatController.isPlanHorizonIntent("shorten plan to 5 days"));
        assertTrue(ChatController.isPlanHorizonIntent("extend study plan by 1 week"));
    }

    @Test
    void detectsPriorityIntentAndExtractsSubjects() {
        List<String> subjects = List.of("Math", "Physics", "Chemistry");

        assertTrue(ChatController.isPlanPriorityIntent("ưu tiên môn Math", subjects));
        assertTrue(ChatController.isPlanPriorityIntent("dồn thời gian cho môn Physics", subjects));
        assertTrue(ChatController.isPlanPriorityIntent("tập trung vào môn Math", subjects));
        assertTrue(ChatController.isPlanPriorityIntent("prioritize Chemistry", subjects));
        assertTrue(ChatController.isPlanPriorityIntent("more time for Math", subjects));

        assertEquals(List.of("Math"),
                ChatController.extractPrioritySubjects("rút ngắn kế hoạch đến ngày 25 và ưu tiên môn Math", subjects));
        assertEquals(List.of("Physics"),
                ChatController.extractPrioritySubjects("kéo dài thêm 3 ngày và dồn thời gian cho Physics", subjects));
        assertEquals(List.of("Math", "Chemistry"),
                ChatController.extractPrioritySubjects("ưu tiên cả Math và Chemistry", subjects));
    }

    @Test
    void rejectsNonPriorityRequests() {
        List<String> subjects = List.of("Math", "Physics");
        assertFalse(ChatController.isPlanPriorityIntent("hôm nay học Math lúc 8h", subjects));
        assertFalse(ChatController.isPlanPriorityIntent("kế hoạch có bao nhiêu buổi", subjects));
        assertFalse(ChatController.isPlanPriorityIntent("", subjects));
        assertFalse(ChatController.isPlanPriorityIntent(null, subjects));
    }

    @Test
    void detectsDropSubjectIntentAndExtractsSubjects() {
        List<String> subjects = List.of("Math", "Physics", "Chemistry");

        assertTrue(ChatController.isPlanDropSubjectIntent("bỏ môn Math", subjects));
        assertTrue(ChatController.isPlanDropSubjectIntent("xóa môn Physics khỏi lịch", subjects));
        assertTrue(ChatController.isPlanDropSubjectIntent("không học môn Chemistry nữa", subjects));
        assertTrue(ChatController.isPlanDropSubjectIntent("drop Chemistry", subjects));

        assertEquals(List.of("Math"),
                ChatController.extractDroppedSubjects("bỏ môn Math ra khỏi kế hoạch", subjects));
        assertEquals(List.of("Physics"),
                ChatController.extractDroppedSubjects("xóa môn Physics", subjects));
    }

    @Test
    void rejectsNonEditRequests() {
        assertFalse(ChatController.isPlanEditIntent("tạo plan cho tuần này", sessions));
        assertFalse(ChatController.isPlanEditIntent("I need a new study schedule", sessions));
        assertFalse(ChatController.isPlanEditIntent("", sessions));
        assertFalse(ChatController.isPlanEditIntent(null, sessions));
    }
}
