package io.github.realmlabs.asteria.protocol.protobuf.gradle

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class AsteriaProtobufProtocolCodegenExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val asteriaVersion: Property<String> = objects.property(String::class.java)
    val addDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val packageName: Property<String> = objects.property(String::class.java)
        .convention("io.github.realmlabs.asteria.generated.protocol")

    val gateway: GatewayProtocolCodegenExtension = objects.newInstance(GatewayProtocolCodegenExtension::class.java)
    val rpc: RpcProtocolCodegenExtension = objects.newInstance(RpcProtocolCodegenExtension::class.java)

    fun gateway(configure: Action<GatewayProtocolCodegenExtension>) {
        configure.execute(gateway)
    }

    fun rpc(configure: Action<RpcProtocolCodegenExtension>) {
        configure.execute(rpc)
    }
}

abstract class GatewayProtocolCodegenExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val metadataFile: RegularFileProperty = objects.fileProperty()
    val descriptorSetFile: RegularFileProperty = objects.fileProperty()
    val packageName: Property<String> = objects.property(String::class.java)
    val className: Property<String> = objects.property(String::class.java).convention("GeneratedGatewayProtocol")
    val clientMetadataEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val clientMetadataFile: RegularFileProperty = objects.fileProperty()
}

abstract class RpcProtocolCodegenExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val metadataFile: RegularFileProperty = objects.fileProperty()
    val packageName: Property<String> = objects.property(String::class.java)
    val className: Property<String> = objects.property(String::class.java).convention("GeneratedRpcProtocol")
}
