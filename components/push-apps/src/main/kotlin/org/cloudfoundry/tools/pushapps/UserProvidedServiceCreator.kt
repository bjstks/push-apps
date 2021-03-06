package org.cloudfoundry.tools.pushapps

import org.apache.logging.log4j.LogManager
import org.cloudfoundry.tools.pushapps.config.UserProvidedServiceConfig
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class UserProvidedServiceCreator(
    private val cloudFoundryClient: CloudFoundryClient,
    private val serviceConfigs: List<UserProvidedServiceConfig>,
    private val maxInFlight: Int
) {
    private val logger = LogManager.getLogger(UserProvidedServiceCreator::class.java)

    fun createOrUpdateServices(): Flux<OperationResult> {
        val serviceNames = serviceConfigs.map(UserProvidedServiceConfig::name)
        logger.info("Creating user provided services: ${serviceNames.joinToString(", ")}.")

        val existingServiceNames = cloudFoundryClient
            .listServices()
            .toIterable()
            .toList()

        val createServiceOperation = { serviceConfig: UserProvidedServiceConfig ->
            createUserProvidedService(existingServiceNames, serviceConfig)
        }

        return scheduleOperations(
            configs = serviceConfigs,
            maxInFlight = maxInFlight,
            operation = createServiceOperation,
            operationIdentifier = UserProvidedServiceConfig::name,
            operationDescription = { service -> "Create user provided service ${service.name}" }
        )
    }

    private fun createUserProvidedService(
        existingServices: List<String>,
        serviceConfig: UserProvidedServiceConfig
    ): Mono<OperationResult> {
        val serviceCommand = if (existingServices.contains(serviceConfig.name)) {
            cloudFoundryClient.updateUserProvidedService(serviceConfig)
        } else {
            cloudFoundryClient.createUserProvidedService(serviceConfig)
        }

        val description = "Creating user provided service ${serviceConfig.name}"
        val operationResult = OperationResult(
            description = description,
            didSucceed = true,
            operationConfig = serviceConfig
        )

        return serviceCommand
            .transform(logAsyncOperation(logger, description))
            .then(Mono.just(operationResult))
    }
}
