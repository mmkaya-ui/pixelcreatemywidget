package dev.afterdrop.ai

import dev.afterdrop.composer.ChatPrompt
import dev.afterdrop.composer.Composer
import dev.afterdrop.composer.Describe
import dev.afterdrop.composer.Described
import dev.afterdrop.spec.Problem
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import dev.afterdrop.spec.WidgetSpec
import java.time.LocalDate

/** Any text model: give it a prompt, get its answer or a reason it could not answer. */
interface TextModel {
    fun complete(prompt: String): Reply
}

sealed interface Reply {
    class Text(val text: String) : Reply
    /** [retryable] is false for problems another attempt cannot fix, such as a bad key. */
    class Failed(val reason: String, val retryable: Boolean = false) : Reply
}

sealed interface Outcome {
    /** [by] is "model" or "rules"; [attempts] counts model calls; [note] explains a fallback. */
    class Ok(val spec: WidgetSpec, val by: String, val attempts: Int, val note: String? = null) : Outcome
    class Failed(val reason: String) : Outcome
}

/**
 * Turns a description into a validated widget with a model in the loop.
 * The model's answer is only ever text: it becomes a widget solely by passing [SpecReader].
 * A rejected answer goes back to the model with the reasons, at most [MAX_REPAIRS] times.
 * If the model cannot produce a valid widget, the offline rules get a turn, so a common request
 * still succeeds without the model.
 */
object Generator {
    const val MAX_REPAIRS = 2

    fun generate(description: String, model: TextModel, today: LocalDate): Outcome {
        val (spec, attempts, problem) = converse(ChatPrompt.build(description), model)
        if (spec != null) return Outcome.Ok(spec, "model", attempts)
        // One altitude down: the offline rules.
        val described = Describe.fromText(description, today)
        if (described is Described.Ok) {
            val composed = Composer.compose(described.brief)
            if (composed is SpecResult.Ok) {
                return Outcome.Ok(composed.spec, "rules", attempts, "Built with the offline rules because $problem.")
            }
        }
        return Outcome.Failed(problem.replaceFirstChar { it.uppercase() } + ".")
    }

    /**
     * Changes an existing widget as the person asks, for example "make the number bigger and use the
     * secondary colour". The current widget is given to the model as data; the result is validated like
     * any other. There is no offline fallback: if the model cannot do it, the widget stays as it was.
     */
    fun edit(current: WidgetSpec, instruction: String, model: TextModel): Outcome {
        val (spec, attempts, problem) = converse(editPrompt(current, instruction), model)
        return if (spec != null) Outcome.Ok(spec, "model", attempts) else Outcome.Failed(problem.replaceFirstChar { it.uppercase() } + ".")
    }

    internal fun editPrompt(current: WidgetSpec, instruction: String): String =
        ChatPrompt.build("Change the widget below as follows: " + instruction.trim().take(500) +
            "\nKeep everything else as it is, including its name, sources and variables, unless the change needs otherwise.") +
            "\n\nCURRENT WIDGET\n" + dev.afterdrop.spec.Json.write(current.json)

    /** Runs the ask-validate-repair loop. Returns the valid spec or null, the number of model calls, and the last problem. */
    private fun converse(first: String, model: TextModel): Triple<WidgetSpec?, Int, String> {
        var prompt = first
        var attempts = 0
        var problem = "the model gave no usable answer"
        while (attempts <= MAX_REPAIRS) {
            attempts++
            when (val reply = model.complete(prompt)) {
                is Reply.Failed -> {
                    problem = reply.reason
                    if (!reply.retryable) break
                }
                is Reply.Text -> when (val read = SpecReader.read(reply.text, lenient = true)) {
                    is SpecResult.Ok -> return Triple(read.spec, attempts, "")
                    is SpecResult.Rejected -> {
                        problem = "the model's widget was not valid: " + read.problems.first().message
                        prompt = first + "\n\nYOUR PREVIOUS ANSWER WAS REJECTED\n" +
                            read.problems.take(12).joinToString("\n") { "- at ${it.path}: ${it.message}" } +
                            "\n\nYour previous answer:\n" + reply.text.take(6_000) +
                            "\n\nReply with the corrected JSON object only."
                    }
                }
            }
        }
        return Triple(null, attempts, problem)
    }
}
