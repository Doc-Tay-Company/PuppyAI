package com.puppy.service;

import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusExtraction;

import java.util.List;

/** Reads timetable and task data from attached documents. */
public interface FocusPlanExtractor {

    FocusExtraction extract(List<DocumentAttachment> documents) throws FocusPlanningException;
}
