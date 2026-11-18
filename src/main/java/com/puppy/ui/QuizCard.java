package com.puppy.ui;

import com.puppy.model.QuizAttempt;
import com.puppy.model.QuizDefinition;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Predicate;

/** An independent learning card; only its definition, never this view or attempt, is persisted. */
public final class QuizCard extends VBox {
    private enum View { QUESTION, RESULTS, REVIEW }

    private final QuizAttempt attempt;
    private final Runnable focusChat;
    private final Predicate<String> confirm;
    private View view = View.QUESTION;

    public QuizCard(QuizAttempt attempt, Runnable focusChat) {
        this(attempt, focusChat, null);
    }

    public QuizCard(QuizAttempt attempt, Runnable focusChat, Predicate<String> confirmation) {
        super(10);
        this.attempt = Objects.requireNonNull(attempt);
        this.focusChat = Objects.requireNonNull(focusChat);
        this.confirm = confirmation == null ? this::confirmAction : confirmation;
        getStyleClass().add("quiz-card");
        setMaxWidth(640);
        setMinWidth(0);
        render();
    }

    private boolean confirmAction(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Puppy quiz");
        alert.setHeaderText(null);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void render() {
        getChildren().clear();
        getChildren().add(label(attempt.definition().title(), "quiz-title"));
        if (view == View.RESULTS) {
            renderResults();
        } else {
            renderQuestion();
        }
    }

    private void renderQuestion() {
        int index = attempt.index();
        QuizDefinition.Question question = attempt.definition().questions().get(index);
        boolean review = view == View.REVIEW;
        boolean checked = attempt.checked(index);
        FlowPane progress = new FlowPane(10, 4);
        progress.getChildren().addAll(label("Question " + (index + 1) + " of "
                        + attempt.definition().questions().size(), "quiz-progress"),
                label(question.difficulty().name(), "status-pill"),
                label("Answered: " + attempt.answeredCount(), "muted-copy"),
                label("Correct: " + attempt.correctCount(), "muted-copy"),
                label("Incorrect: " + attempt.incorrectCount(), "muted-copy"));
        getChildren().add(progress);
        getChildren().add(MarkdownRenderer.render(question.text()));

        Button check = button("Check answer", "primary-button", () -> {
            attempt.check();
            render();
        });
        check.getStyleClass().add("quiz-check");
        check.setDisable(attempt.selection(index) < 0);
        ToggleGroup choices = new ToggleGroup();
        for (int option = 0; option < question.options().size(); option++) {
            int selectedOption = option;
            String text = (char) ('A' + option) + ". " + question.options().get(option);
            RadioButton choice = new RadioButton(text);
            choice.getStyleClass().add("quiz-option");
            choice.setToggleGroup(choices);
            choice.setWrapText(true);
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setMinWidth(0);
            choice.setSelected(attempt.selection(index) == option);
            choice.setDisable(checked || review);
            if (checked || review) {
                if (option == question.correctIndex()) {
                    choice.getStyleClass().add("quiz-option-correct");
                    choice.setAccessibleText(text + ". Correct answer.");
                } else if (checked && option == attempt.selection(index)) {
                    choice.getStyleClass().add("quiz-option-incorrect");
                    choice.setAccessibleText(text + ". Your incorrect answer.");
                }
            }
            choice.setOnAction(event -> {
                attempt.select(selectedOption);
                check.setDisable(false);
            });
            getChildren().add(choice);
        }
        if (checked || review) {
            String correct = "Correct answer: " + (char) ('A' + question.correctIndex()) + ".";
            String feedback = !checked ? "Skipped (not checked). " + correct
                    : attempt.selection(index) == question.correctIndex() ? "Correct. " + correct
                    : "Incorrect. " + correct;
            getChildren().add(label(feedback, "quiz-feedback"));
            getChildren().add(MarkdownRenderer.render(question.explanation()));
        } else {
            getChildren().add(check);
        }

        Button previous = button("Previous", "quiet-button", () -> {
            attempt.previous();
            render();
        });
        previous.setDisable(index == 0);
        boolean last = index == attempt.definition().questions().size() - 1;
        Button next = button(last && !review ? "Finish" : "Next", "primary-button", () -> {
            if (last && !review) {
                finish();
            } else {
                attempt.next();
                render();
            }
        });
        next.setDisable(last && review);
        FlowPane navigation = new FlowPane(8, 6, previous, next);
        if (review) {
            navigation.getChildren().add(button("Back to results", "quiet-button", () -> {
                view = View.RESULTS;
                render();
            }));
        }
        getChildren().add(navigation);
        getChildren().add(label("Answers and scores are not saved when Puppy exits.", "muted-copy"));
    }

    private void finish() {
        int skipped = attempt.skippedCount();
        if (skipped > 0 && !confirm.test("Finish with " + skipped
                + " unchecked question(s)? They will be skipped and earn zero points.")) {
            return;
        }
        attempt.finish(skipped > 0);
        view = View.RESULTS;
        render();
    }

    private void renderResults() {
        getChildren().add(label("Score", "quiz-progress"));
        getChildren().add(label(attempt.correctCount() + " / " + attempt.definition().questions().size(), "quiz-score"));
        FlowPane totals = new FlowPane(10, 4,
                label("Correct: " + attempt.correctCount(), "quiz-total"),
                label("Incorrect: " + attempt.incorrectCount(), "quiz-total"),
                label("Skipped: " + attempt.skippedCount(), "quiz-total"));
        getChildren().add(totals);
        getChildren().add(label("Review the explanations or try the same questions again.", "muted-copy"));
        Button retry = button("Retry quiz", "primary-button", () -> {
            if (attempt.hasSelections() && !confirm.test("Clear this quiz's answers and start a fresh attempt?")) {
                return;
            }
            attempt.reset();
            view = View.QUESTION;
            render();
        });
        Button review = button("Review", "quiet-button", () -> {
            while (attempt.index() > 0) {
                attempt.previous();
            }
            view = View.REVIEW;
            render();
        });
        getChildren().add(new FlowPane(8, 6, retry, review,
                button("Back to chat", "quiet-button", focusChat)));
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }
}
