package lk.quizlink.quiz;

import lk.quizlink.common.model.Question;

/** Agreed starting rule: full question points for a correct answer, zero otherwise. */
final class ScoringRule {
    private ScoringRule() {
    }

    static int pointsFor(Question question, int selectedOptionIndex) {
        return selectedOptionIndex == question.correctOptionIndex() ? question.points() : 0;
    }
}
