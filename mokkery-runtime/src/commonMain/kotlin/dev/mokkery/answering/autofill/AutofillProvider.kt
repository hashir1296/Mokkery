@file:Suppress("NOTHING_TO_INLINE")
package dev.mokkery.answering.autofill

import dev.mokkery.answering.autofill.AutofillProvider.Companion.register
import dev.mokkery.answering.autofill.AutofillProvider.Companion.registerProvider
import dev.mokkery.internal.answering.autofill.AnyValueProvider
import dev.mokkery.internal.answering.autofill.CombinedProviders
import dev.mokkery.internal.answering.autofill.GenericArrayProvider
import dev.mokkery.internal.answering.autofill.NothingValueProvider
import dev.mokkery.internal.answering.autofill.TypeToFunctionAutofillProvider
import dev.mokkery.internal.answering.autofill.TypeToValueAutofillProvider
import dev.mokkery.internal.answering.autofill.buildInTypesMapping
import dev.mokkery.internal.synchronizedMapOf
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlin.reflect.KClass

/**
 * Provides a value whenever there is a call to not mocked function in [dev.mokkery.MockMode.autofill].
 */
public fun interface AutofillProvider<out T> {

    /**
     * Returns an instance of [Value.Provided], whenever value of [type] can be provided.
     * Otherwise, it returns [Value.Absent]
     */
    public fun provide(type: KClass<*>): Value<T>

    /**
     * Result of [AutofillProvider.provide] call.
     */
    public sealed interface Value<out T> {

        /**
         * Represents provided [value].
         */
        public class Provided<out T>(public val value: T) : Value<T> {

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other == null || this::class != other::class) return false
                other as Provided<*>
                return value == other.value
            }

            override fun hashCode(): Int = value?.hashCode() ?: 0

            override fun toString(): String = "Provided(value=$value)"
        }

        /**
         * Indicates that value could not be provided.
         */
        public data object Absent : Value<Nothing>

        public companion object {

            /**
             * Returns an instance of [Provided] if [value] is not null. Otherwise, it returns [Absent].
             */
            public inline fun <T : Any> providedIfNotNull(value: () -> T?) : Value<T> = value()
                ?.let(::Provided)
                ?: Absent
        }
    }

    /**
     * Implementation of [AutofillProvider] that is used by [dev.mokkery.answering.Answer.Autofill].
     * It provides defaults and also allows registering custom providers.
     *
     * Lookup order:
     * * Custom implementations of [AutofillProvider] registered with [registerProvider].
     * * Simplified type-to-function providers registered with [register]
     * * Defaults
     *
     * By default, it provides:
     * * For any [Number] - 0
     * * For [Boolean] - false
     * * For [String] - ""
     * * For [KClass] - Any::class
     * * For [Unit] - [Unit]
     * * For complex types:
     *    * JVM and Wasm - null
     *    * JS - empty object `{}`
     *    * Native - reference to `object UnsafeValue`
     *  * For arrays - array with single element according to previous rules
     */
    public companion object : AutofillProvider<Any?> {

        internal val builtIn = CombinedProviders(
            TypeToValueAutofillProvider(buildInTypesMapping),
            NothingValueProvider,
            GenericArrayProvider,
            AnyValueProvider
        )

        private val registeredTypes = synchronizedMapOf<KClass<*>, () -> Any>()
        private val delegate = atomic(CombinedProviders(TypeToFunctionAutofillProvider(registeredTypes), builtIn))

        override fun provide(type: KClass<*>): Value<Any?> = delegate.value.provide(type)

        /**
         * Registers [provider] to be used before previously registered providers (including initial defaults).
         */
        public fun registerProvider(provider: AutofillProvider<*>) {
            delegate.update { it.withFirst(provider) }
        }

        /**
         * Unregisters [provider] registered with [registerProvider] so it is no longer in use.
         */
        public fun unregisterProvider(provider: AutofillProvider<*>) {
            delegate.update { it.without(provider) }
        }

        /**
         * Registers a [provider] for [type].
         * It overwrites any provider registered for the same [type] with this method.
         * It's used after providers registered with [registerProvider].
         */
        public fun <T : Any> register(type: KClass<T>, provider: () -> T) {
            registeredTypes[type] = provider
        }

        /**
         * Unregisters provider registered with [register] for [type] so it is no longer in use.
         */
        public fun unregister(type: KClass<*>) {
            registeredTypes.remove(type)
        }
    }
}

/**
 * Calls [AutofillProvider.register] with type [T].
 */
public inline fun <reified T : Any> AutofillProvider.Companion.register(noinline provider: () -> T) {
    register(T::class, provider)
}

/**
 * Calls [AutofillProvider.unregister] with type [T].
 */
public inline fun <reified T : Any> AutofillProvider.Companion.unregister() {
    unregister(T::class)
}

/**
 * Calls [AutofillProvider.provide] and returns [AutofillProvider.Value.Provided.value] if present.
 */
public inline fun <T> AutofillProvider<T>.provideValue(type: KClass<*>): T? = provide(type).getIfProvided()

/**
 * Returns value if [this] is an instance of [AutofillProvider.Value.Provided].
 */
public fun <T> AutofillProvider.Value<T>.getIfProvided(): T? = (this as? AutofillProvider.Value.Provided<T>)?.value

/**
 * Returns true if [this] is an instance of [AutofillProvider.Value.Provided]
 */
public fun AutofillProvider.Value<*>.isProvided(): Boolean = this != AutofillProvider.Value.Absent
