package com.puppy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubjectNamesTest {

    @Test
    void mapsVietnameseAndEnglishNamesToTheSameSubject() {
        assertEquals("Math", SubjectNames.canonical("Toán"));
        assertEquals("Math", SubjectNames.canonical("math"));
        assertEquals(SubjectNames.key("Toán"), SubjectNames.key("Math"));
        assertEquals("Physics", SubjectNames.canonical("Vật lý"));
        assertEquals(SubjectNames.key("Vật lý"), SubjectNames.key("Physics"));
        assertEquals("Chemistry", SubjectNames.canonical("Hóa học"));
        assertEquals(SubjectNames.key("Hóa học"), SubjectNames.key("chemistry"));
    }

    @Test
    void keepsUnknownNamesAndNumberedVariantsReadable() {
        assertEquals("Machine Learning", SubjectNames.canonical("  Machine   Learning "));
        assertEquals("Math 2", SubjectNames.canonical("Toán 2"));
        assertEquals(SubjectNames.key("Math 2"), SubjectNames.key("toan 2"));
    }
}
