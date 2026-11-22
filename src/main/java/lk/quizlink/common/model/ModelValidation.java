package lk.quizlink.common.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small validation helpers shared by the immutable common model records. */
final class ModelValidation {
    private ModelValidation() {
    }

    static String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    static String displayName(String value) {
        requiredText(value, "displayName");
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
        return value;
    }

    static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be null or empty");
        }
        if (values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(fieldName + " must not contain null values");
        }
        return List.copyOf(values);
    }

    static List<String> copyOptions(List<String> options) {
        List<String> copy = copyList(options, "options");
        for (String option : copy) {
            requiredText(option, "option");
        }
        return copy;
    }

    static List<Question> copyQuestions(List<Question> questions) {
        List<Question> copy = copyList(questions, "questions");
        Set<String> questionIds = new HashSet<>();
        for (Question question : copy) {
            if (!questionIds.add(question.id())) {
                throw new IllegalArgumentException("questions must not contain duplicate IDs");
            }
        }
        return copy;
    }

}
