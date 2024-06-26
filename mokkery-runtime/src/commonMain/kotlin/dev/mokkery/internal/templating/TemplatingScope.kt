package dev.mokkery.internal.templating

import dev.mokkery.annotations.DelicateMokkeryApi
import dev.mokkery.answering.autofill.AutofillProvider
import dev.mokkery.answering.autofill.provideValue
import dev.mokkery.internal.ConcurrentTemplatingException
import dev.mokkery.internal.MokkerySpyScope
import dev.mokkery.internal.VarargsAmbiguityDetectedException
import dev.mokkery.internal.asListOrNull
import dev.mokkery.internal.matcher.ArgMatchersComposer
import dev.mokkery.internal.signature.SignatureGenerator
import dev.mokkery.internal.subListAfter
import dev.mokkery.internal.tracing.CallArg
import dev.mokkery.internal.unsafeCast
import dev.mokkery.matcher.ArgMatcher
import dev.mokkery.matcher.ArgMatchersScope
import kotlin.reflect.KClass

internal interface TemplatingScope : ArgMatchersScope {

    val spies: Set<MokkerySpyScope>
    val templates: List<CallTemplate>

    fun ensureBinding(token: Int, obj: Any?)

    fun <T> interceptArg(token: Int, name: String, arg: T): T

    fun <T> interceptVarargElement(token: Int, arg: T, isSpread: Boolean): T

    fun saveTemplate(receiver: String, name: String, args: List<CallArg>)

    fun release()
}

internal fun TemplatingScope(
    signatureGenerator: SignatureGenerator = SignatureGenerator(),
    composer: ArgMatchersComposer = ArgMatchersComposer(),
    binder: TemplatingScopeDataBinder = TemplatingScopeDataBinder(),
    autofill: AutofillProvider<Any?> = AutofillProvider.builtIn
): TemplatingScope = TemplatingScopeImpl(
    signatureGenerator = signatureGenerator,
    composer = composer,
    binder = binder,
    autofill = autofill
)

private class TemplatingScopeImpl(
    private val signatureGenerator: SignatureGenerator,
    private val composer: ArgMatchersComposer,
    private val binder: TemplatingScopeDataBinder,
    private val autofill: AutofillProvider<Any?>
) : TemplatingScope {
    private var isReleased = false
    private val currentArgMatchers = mutableListOf<ArgMatcher<Any?>>()

    override val spies = mutableSetOf<MokkerySpyScope>()
    override val templates = mutableListOf<CallTemplate>()

    override fun ensureBinding(token: Int, obj: Any?) {
        if (isReleased) return
        val scope = binder.bind(token, obj) ?: return
        val templating = scope.interceptor.templating
        when {
            templating.isEnabledWith(this) -> return
            templating.isEnabled -> throw ConcurrentTemplatingException()
            else -> {
                spies.add(scope)
                templating.start(this)
            }
        }
    }

    override fun release() {
        isReleased = true
        spies.forEach { it.interceptor.templating.stop() }
        spies.clear()
    }

    @DelicateMokkeryApi
    override fun <T> matches(argType: KClass<*>, matcher: ArgMatcher<T>): T {
        if (isReleased) return autofill.provideValue(argType).unsafeCast()
        currentArgMatchers.add(matcher.unsafeCast())
        return autofill.provideValue(argType).unsafeCast()
    }

    override fun <T> interceptVarargElement(token: Int, arg: T, isSpread: Boolean): T {
        if (isReleased) return arg
        val data = binder.getDataFor(token) ?: return arg
        val args = when {
            isSpread -> arg.asListOrNull() ?: error("Expected array, but $arg encountered!")
            else -> listOf(arg)
        }
        val size = args.size
        val elementMatchersSize = currentArgMatchers.subListAfter(data.varargMatchersCount).size
        if (elementMatchersSize != 0 && elementMatchersSize < size) throw VarargsAmbiguityDetectedException()
        args.forEachIndexed { index, vararg ->
            currentArgMatchers.getOrNull(data.varargMatchersCount + index)
                ?: currentArgMatchers.add(ArgMatcher.Equals(vararg))
        }
        data.varargMatchersCount += elementMatchersSize
        return arg
    }

    override fun <T> interceptArg(token: Int, name: String, arg: T): T {
        if (isReleased) return arg
        val data = binder.getDataFor(token) ?: return arg
        data.matchers[name] = currentArgMatchers.toMutableList()
        currentArgMatchers.clear()
        return arg
    }

    override fun saveTemplate(receiver: String, name: String, args: List<CallArg>) {
        if (isReleased) return
        val matchers = flush(args)
        templates += CallTemplate(receiver, name, signatureGenerator.generate(name, args), matchers.toMap())
    }

    private fun flush(args: List<CallArg>): List<Pair<String, ArgMatcher<Any?>>> {
        val namedMatchers = binder.firstProperlyBoundedData()
            .matchers
            .toMutableMap()
        currentArgMatchers.clear()
        binder.reset()
        return args.map {
            val matchers = namedMatchers[it.name].orEmpty()
            it.name to composer.compose(it, matchers)
        }
    }
}
