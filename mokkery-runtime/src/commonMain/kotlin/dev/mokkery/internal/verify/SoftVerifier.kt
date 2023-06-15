package dev.mokkery.internal.verify

import dev.mokkery.internal.templating.CallTemplate
import dev.mokkery.internal.tracing.CallTrace
import dev.mokkery.internal.tracing.matches

internal class SoftVerifier(private val atLeast: Int, private val atMost: Int) : Verifier {
    override fun verify(callTraces: List<CallTrace>, callTemplates: List<CallTemplate>): List<CallTrace> {
        val verified = callTemplates.flatMap { template ->
            val matching = callTraces.filter { it matches template }
            if (matching.size < atLeast || matching.size > atMost) {
                failAssertion(callTraces, callTemplates) {
                    "Expected calls count to be in range $atLeast..$atMost, but is ${matching.size} for $template!"
                }
            }
            matching
        }
        return verified
    }
}
