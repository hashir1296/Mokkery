package dev.mokkery.plugin

import dev.mokkery.plugin.transformers.CallTrackingTransformer
import dev.mokkery.plugin.transformers.MockCallsTransformer
import dev.mokkery.plugin.transformers.SpyCallsTransformer
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
class MokkeryIrGenerationExtension(
    private val messageCollector: MessageCollector
) : IrGenerationExtension {

    private val mockTable = mutableMapOf<IrClass, IrClass>()
    private val spyTable = mutableMapOf<IrClass, IrClass>()

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val time = measureTime {
            moduleFragment.files.forEach { irFile ->
                MockCallsTransformer(
                    pluginContext = pluginContext,
                    messageCollector = messageCollector,
                    irFile = irFile,
                    mockTable = mockTable
                ).visitFile(irFile)
                SpyCallsTransformer(
                    pluginContext = pluginContext,
                    messageCollector = messageCollector,
                    irFile = irFile,
                    spyTable = spyTable,
                ).visitFile(irFile)
            }
            val interceptedTypesTable = mockTable + spyTable
            moduleFragment.files.forEach { irFile ->
                CallTrackingTransformer(
                    messageCollector = messageCollector,
                    irFile = irFile,
                    pluginContext = pluginContext,
                    table = interceptedTypesTable
                ).visitFile(irFile)
            }
        }
        messageCollector.info { "Plugin time: $time" }
    }
}
