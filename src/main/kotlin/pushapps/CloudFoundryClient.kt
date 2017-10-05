package pushapps

import org.cloudfoundry.client.v2.securitygroups.CreateSecurityGroupRequest
import org.cloudfoundry.client.v2.securitygroups.Protocol
import org.cloudfoundry.client.v2.securitygroups.RuleEntity
import org.cloudfoundry.client.v2.securitygroups.SecurityGroupResource
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.DefaultCloudFoundryOperations
import org.cloudfoundry.operations.applications.SetEnvironmentVariableApplicationRequest
import org.cloudfoundry.operations.applications.StartApplicationRequest
import org.cloudfoundry.operations.applications.StopApplicationRequest
import org.cloudfoundry.operations.organizations.CreateOrganizationRequest
import org.cloudfoundry.operations.organizations.OrganizationSummary
import org.cloudfoundry.operations.routes.MapRouteRequest
import org.cloudfoundry.operations.routes.UnmapRouteRequest
import org.cloudfoundry.operations.services.*
import org.cloudfoundry.operations.spaces.CreateSpaceRequest
import org.cloudfoundry.operations.spaces.GetSpaceRequest
import org.cloudfoundry.operations.spaces.SpaceDetail
import org.cloudfoundry.operations.spaces.SpaceSummary
import reactor.core.publisher.Mono

class CloudFoundryClient(
    private val cfConfig: CfConfig,

    private var cloudFoundryOperations: CloudFoundryOperations = cloudFoundryOperationsBuilder()
        .apply {
            this.apiHost = cfConfig.apiHost
            this.username = cfConfig.username
            this.password = cfConfig.password
            this.skipSslValidation = cfConfig.skipSslValidation
            this.dialTimeoutInMillis = cfConfig.dialTimeoutInMillis
        }
        .build()
) {

    fun createService(serviceConfig: ServiceConfig): Mono<Void> {
        val createServiceRequest = CreateServiceInstanceRequest
            .builder()
            .serviceInstanceName(serviceConfig.name)
            .planName(serviceConfig.plan)
            .serviceName(serviceConfig.broker)
            .build()

        return cloudFoundryOperations
            .services()
            .createInstance(createServiceRequest)
    }

    fun createUserProvidedService(serviceConfig: UserProvidedServiceConfig): Mono<Void> {
        val createServiceRequest = CreateUserProvidedServiceInstanceRequest
            .builder()
            .name(serviceConfig.name)
            .credentials(serviceConfig.credentials)
            .build()

        return cloudFoundryOperations
            .services()
            .createUserProvidedInstance(createServiceRequest)
    }

    fun updateUserProvidedService(serviceConfig: UserProvidedServiceConfig): Mono<Void> {
        val updateServiceRequest = UpdateUserProvidedServiceInstanceRequest
            .builder()
            .userProvidedServiceInstanceName(serviceConfig.name)
            .credentials(serviceConfig.credentials)
            .build()

        return cloudFoundryOperations
            .services()
            .updateUserProvidedInstance(updateServiceRequest)
    }

    fun pushApplication(appConfig: AppConfig): Mono<Void> {
        val pushApplication = PushApplication(cloudFoundryOperations, appConfig)
        return pushApplication.generatePushAppAction()
    }

    fun startApplication(appName: String): Mono<Void> {
        val startApplicationRequest = StartApplicationRequest.builder().name(appName).build()
        return cloudFoundryOperations.applications().start(startApplicationRequest)
    }

    fun stopApplication(appName: String): Mono<Void> {
        val stopApplicationRequest = StopApplicationRequest
            .builder()
            .name(appName)
            .build()

        return cloudFoundryOperations
            .applications()
            .stop(stopApplicationRequest)
    }

    fun setApplicationEnvironment(appConfig: AppConfig): List<Mono<Void>> {
        val setEnvRequests = generateSetEnvRequests(appConfig)

        return setEnvRequests.map { request ->
            cloudFoundryOperations
                .applications()
                .setEnvironmentVariable(request)
        }
    }

    private fun generateSetEnvRequests(appConfig: AppConfig): Array<SetEnvironmentVariableApplicationRequest> {
        if (appConfig.environment === null) return emptyArray()

        return appConfig.environment.map { variable ->
            SetEnvironmentVariableApplicationRequest
                .builder()
                .name(appConfig.name)
                .variableName(variable.key)
                .variableValue(variable.value)
                .build()
        }.toTypedArray()
    }

    //TODO should this just return one action at a time?
    fun bindServicesToApplication(appName: String, serviceNames: List<String>): List<Mono<Void>> {
        val bindServiceRequests = generateBindServiceRequests(appName, serviceNames)

        return bindServiceRequests.map { request ->
            cloudFoundryOperations
                .services()
                .bind(request)
        }
    }

    private fun generateBindServiceRequests(appName: String, serviceNames: List<String>): Array<BindServiceInstanceRequest> {
        return serviceNames.map { serviceName ->
            BindServiceInstanceRequest
                .builder()
                .applicationName(appName)
                .serviceInstanceName(serviceName)
                .build()
        }.toTypedArray()
    }

    fun mapRoute(appConfig: AppConfig): Mono<Void> {
        if (appConfig.route === null) {
            return Mono.empty()
        }

        //TODO return error mono if domain, hostname, or path don't exist
        val mapRouteRequestBuilder = MapRouteRequest
            .builder()
            .applicationName(appConfig.name)
            .domain(appConfig.domain)
            .host(appConfig.route.hostname)

            if (appConfig.route.path !== null) {
                mapRouteRequestBuilder.path(appConfig.route.path)
            }

            val mapRouteRequest = mapRouteRequestBuilder.build()

        return cloudFoundryOperations.routes().map(mapRouteRequest).ofType(Void.TYPE)
    }

    fun unmapRoute(appConfig: AppConfig): Mono<Void> {
        if (appConfig.route === null) {
            return Mono.empty()
        }

        val unmapRouteRequest = UnmapRouteRequest
            .builder()
            .applicationName(appConfig.name)
            .domain(appConfig.domain)
            .host(appConfig.route.hostname)
            .path(appConfig.route.path)
            .build()

        return cloudFoundryOperations.routes().unmap(unmapRouteRequest)
    }

    fun createSecurityGroup(securityGroup: SecurityGroup, spaceId: String): Mono<Void> {
        val rule = RuleEntity
            .builder()
            .destination(securityGroup.destination)
            .protocol(Protocol.from(securityGroup.protocol))
            .build()

        val createSecurityGroupRequest = CreateSecurityGroupRequest
            .builder()
            .name(securityGroup.name)
            .rule(rule)
            .spaceId(spaceId)
            .build()

        val defaultCloudFoundryOperations = cloudFoundryOperations as DefaultCloudFoundryOperations
        return defaultCloudFoundryOperations.cloudFoundryClient
            .securityGroups()
            .create(createSecurityGroupRequest)
            .ofType(Void.TYPE)
    }

    fun createAndTargetOrganization(organizationName: String): CloudFoundryClient {
        createOrganizationIfDoesNotExist(organizationName)
        return targetOrganization(organizationName)
    }

    private fun createOrganizationIfDoesNotExist(name: String) {
        if (!organizationDoesExist(name)) {
            createOrganization(name)
        }
    }

    private fun organizationDoesExist(name: String) = listOrganizations().indexOf(name) != -1

    private fun createOrganization(name: String) {
        val createOrganizationRequest: CreateOrganizationRequest = CreateOrganizationRequest
            .builder()
            .organizationName(name)
            .build()

        cloudFoundryOperations.organizations().create(createOrganizationRequest).block()
    }

    private fun targetOrganization(organizationName: String): CloudFoundryClient {
        cloudFoundryOperations = cloudFoundryOperationsBuilder()
            .fromExistingOperations(cloudFoundryOperations)
            .apply {
                this.organization = organizationName
            }.build()

        return this
    }

    fun createAndTargetSpace(spaceName: String): CloudFoundryClient {
        createSpaceIfDoesNotExist(spaceName)
        return targetSpace(spaceName)
    }

    private fun createSpaceIfDoesNotExist(name: String) {
        if (!spaceDoesExist(name)) {
            createSpace(name)
        }
    }

    private fun spaceDoesExist(name: String) = listSpaces().indexOf(name) != -1

    private fun createSpace(name: String) {
        val createSpaceRequest: CreateSpaceRequest = CreateSpaceRequest
            .builder()
            .name(name)
            .build()

        cloudFoundryOperations.spaces().create(createSpaceRequest).block()
    }

    private fun targetSpace(space: String): CloudFoundryClient {
        cloudFoundryOperations = cloudFoundryOperationsBuilder()
            .fromExistingOperations(cloudFoundryOperations)
            .apply {
                this.space = space
            }.build()

        return this
    }

    fun listOrganizations(): List<String> {
        val orgFlux = cloudFoundryOperations
            .organizations()
            .list()
            .map(OrganizationSummary::getName)

        return orgFlux
            .toIterable()
            .toList()
    }

    fun listSpaces(): List<String> {
        val spaceFlux = cloudFoundryOperations
            .spaces()
            .list()
            .map(SpaceSummary::getName)

        return spaceFlux
            .toIterable()
            .toList()
    }

    fun listServices(): List<String> {
        val serviceInstanceFlux = cloudFoundryOperations
            .services()
            .listInstances()
            .map(ServiceInstanceSummary::getName)

        return serviceInstanceFlux
            .toIterable()
            .toList()
    }

    fun getSpaceId(spaceName: String): String {
        val spaceRequest = GetSpaceRequest
            .builder()
            .name(spaceName)
            .build()

        val spaceId = cloudFoundryOperations
            .spaces()
            .get(spaceRequest)
            .map(SpaceDetail::getId)
            .block()

        return spaceId
    }
}
