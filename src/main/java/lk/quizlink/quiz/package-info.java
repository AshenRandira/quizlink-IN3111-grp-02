/**
 * Member 4: runtime quiz sessions, answer validation, scoring, timers and worker-thread execution.
 *
 * <p>{@link lk.quizlink.quiz.QuizEngine} is the entry point. It processes commands on its own worker
 * pool and question timers on a scheduler, so the NIO Selector thread never performs quiz work.
 * Room-scoped events are delivered through {@link lk.quizlink.quiz.QuizEventSink} as ready-to-send
 * protocol messages built by {@link lk.quizlink.quiz.QuizMessages}.
 */
package lk.quizlink.quiz;
