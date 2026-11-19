package com.puppy.service;

import com.puppy.enums.Priority;
import com.puppy.model.TaskRefinementReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskRefinementJsonParserTest {
    private final TaskRefinementJsonParser parser = new TaskRefinementJsonParser();

    @Test
    void parsesClarificationWithoutCreatingATask() throws Exception {
        TaskRefinementReply reply = parser.parse("""
                {"kind":"CLARIFY","message":"Which Java topic?","issues":["Too broad"]}
                """, null);

        assertEquals(TaskRefinementReply.Kind.CLARIFY, reply.kind());
        assertEquals("Which Java topic?", reply.message());
        assertEquals(1, reply.issues().size());
    }

    @Test
    void parsesEditableProposalAndPreservesExistingId() throws Exception {
        TaskRefinementReply reply = parser.parse("""
                ```json
                {"kind":"PROPOSAL","message":"Ready to review","issues":[],"task":{
                  "title":"Practice Java OOP","description":"Write small examples",
                  "category":"Programming","priority":"HIGH",
                  "completionCriteria":"Four examples run successfully",
                  "checklist":["Encapsulation example","Inheritance example"],
                  "tags":["java","oop"]}}
                ```
                """, "task-7");

        assertEquals(TaskRefinementReply.Kind.PROPOSAL, reply.kind());
        assertEquals("task-7", reply.proposal().id());
        assertEquals(Priority.HIGH, reply.proposal().priority());
        assertEquals(2, reply.proposal().checklist().size());
    }

    @Test
    void rejectsProposalWithoutATitle() {
        assertThrows(TaskRefinementException.class, () -> parser.parse("""
                {"kind":"PROPOSAL","message":"draft","task":{"title":""}}
                """, null));
    }
}
