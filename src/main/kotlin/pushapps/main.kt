package pushapps

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

fun main(args: Array<String>) {
    val logger: Logger = LoggerFactory.getLogger("Push Apps")

    val configPath = ArgumentParser.parseConfigPath(args)
    val (pushApps, cf, apps, services, userProvidedServices) = ConfigReader.parseConfig(configPath)


    //TODO capture errors and print
    val cloudFoundryClient = targetCf(cf)

    if (apps.isEmpty()) {
        logger.error("No apps were provided in the config")
        System.exit(0)
    }

    var availableServices: List<String> = emptyList()
    if (services !== null) {
        availableServices = createServices(services, cloudFoundryClient, logger)
    }

    if (userProvidedServices !== null) {
        createOrUpdateUserProvidedServices(userProvidedServices, cloudFoundryClient, logger)
        availableServices += userProvidedServices.map(UserProvidedServiceConfig::name)
    }

    deployApps(apps, availableServices, pushApps.appDeployRetryCount, cloudFoundryClient, logger)

    logger.info("SUCCESS")
}

private fun targetCf(cf: CfConfig): CloudFoundryClient {
    val cloudFoundryClient = CloudFoundryClient(
        cf.apiHost,
        cf.username,
        cf.password,
        cf.skipSslValidation,
        cf.dialTimeoutInMillis
    )

    return cloudFoundryClient
        .createAndTargetOrganization(cf.organization)
        .createAndTargetSpace(cf.space)
}

fun createServices(services: List<ServiceConfig>, cloudFoundryClient: CloudFoundryClient, logger: Logger): List<String> {
    val serviceCreator = ServiceCreator(cloudFoundryClient, services)
    val createServiceResults = serviceCreator.createServices()

    val didSucceed = didSucceed(createServiceResults)
    if (!didSucceed) {
        handleOperationFailure(createServiceResults, "Creating service", logger)
    }

    return createServiceResults.filter(OperationResult::didSucceed).map(OperationResult::name)
}

private fun createOrUpdateUserProvidedServices(userProvidedServices: List<UserProvidedServiceConfig>, cloudFoundryClient: CloudFoundryClient, logger: Logger) {
    val userProvidedServiceCreator = UserProvidedServiceCreator(cloudFoundryClient, userProvidedServices)
    val createUserServicesResults = userProvidedServiceCreator.createOrUpdateServices()

    val didSucceed = didSucceed(createUserServicesResults)
    if (!didSucceed) {
        handleOperationFailure(createUserServicesResults, "Creating user provided service", logger)
    }
}

private fun deployApps(apps: List<AppConfig>, availableServices: List<String>, retryCount: Int, cloudFoundryClient: CloudFoundryClient, logger: Logger) {
    val appDeployer = AppDeployer(cloudFoundryClient, apps, availableServices, retryCount)
    val results = appDeployer.deployApps()

    val didSucceed = didSucceed(results)
    if (!didSucceed) {
        handleOperationFailure(results, "Deploying application", logger)
    }
}

private fun didSucceed(results: List<OperationResult>): Boolean {
    val didSucceed = results.fold(true, { acc, result -> acc && result.didSucceed })
    return didSucceed
}

private fun handleOperationFailure(results: List<OperationResult>, actionName: String, logger: Logger) {
    val failedResults = results.filterNot(OperationResult::didSucceed)

    failedResults
        .filter(OperationResult::optional)
        .forEach { (name, _, error, _) ->
            logger.warn("$actionName $name was optional and failed with error message: ${error!!.message}")
        }

    val nonOptionalFailedResults = failedResults.filterNot(OperationResult::optional)

    nonOptionalFailedResults
        .forEach { (name, _, error, _) ->
            logger.error("$actionName $name failed with error message: ${error!!.message}")

            if (logger.isDebugEnabled) {
                error.printStackTrace()
            }
        }

    if (nonOptionalFailedResults.isNotEmpty()) {
        System.exit(3)
    }
}
