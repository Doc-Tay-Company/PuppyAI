package com.puppy.model;

import java.util.List;

/**
 * Structured planner data read from a timetable or subject file, plus notes
 * about anything the reader skipped.
 */
public record FocusExtraction(FocusPlannerInput input, List<String> notes) {

    public FocusExtraction {
        input = input == null ? new FocusPlannerInput(List.of(), List.of()) : input;
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
