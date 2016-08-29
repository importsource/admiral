/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.adapter.docker.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static com.vmware.admiral.adapter.docker.mock.MockDockerCreateImageService.REGISTRY_PASSWORD;
import static com.vmware.admiral.adapter.docker.mock.MockDockerCreateImageService.REGISTRY_USER;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.adapter.common.ContainerOperationType;
import com.vmware.admiral.adapter.common.service.mock.MockTaskFactoryService;
import com.vmware.admiral.adapter.common.service.mock.MockTaskService.MockTaskState;
import com.vmware.admiral.adapter.docker.mock.BaseMockDockerTestCase;
import com.vmware.admiral.adapter.docker.mock.MockDockerPathConstants;
import com.vmware.admiral.adapter.docker.util.DockerPortMapping.Protocol;
import com.vmware.admiral.common.AuthCredentialsType;
import com.vmware.admiral.common.DeploymentProfileConfig;
import com.vmware.admiral.common.test.HostInitTestDcpServicesConfig;
import com.vmware.admiral.common.util.CertificateUtil;
import com.vmware.admiral.compute.ComputeConstants;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerFactoryService;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.compute.container.ContainerService.ContainerState.PowerState;
import com.vmware.admiral.compute.container.PortBinding;
import com.vmware.admiral.compute.container.maintenance.ContainerHealthEvaluator;
import com.vmware.admiral.compute.container.maintenance.ContainerStats;
import com.vmware.admiral.host.HostInitCommonServiceConfig;
import com.vmware.admiral.host.HostInitComputeServicesConfig;
import com.vmware.admiral.host.HostInitPhotonModelServiceConfig;
import com.vmware.admiral.service.common.RegistryService;
import com.vmware.admiral.service.common.RegistryService.RegistryState;
import com.vmware.admiral.service.common.ServiceTaskCallback;
import com.vmware.admiral.service.common.SslTrustCertificateService;
import com.vmware.admiral.service.common.SslTrustCertificateService.SslTrustCertificateState;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.ServiceStats;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.AuthCredentialsService;
import com.vmware.xenon.services.common.AuthCredentialsService.AuthCredentialsServiceState;
import com.vmware.xenon.services.common.FileContentService;

/**
 * Test the DockerAdapterService operations (create/delete container)
 */
public class DockerAdapterServiceTest extends BaseMockDockerTestCase {
    private static final String TEST_CUSTOM_PROP_NAME = "Hostname";
    private static final String TEST_CUSTOM_PROP_PATH = "Config."
            + TEST_CUSTOM_PROP_NAME;
    private static final String TEST_CUSTOM_PROP_VALUE = "testhost";
    private static final String TEST_IMAGE_URL_PATH = "/test-files/testimage.tar";
    private static final String TEST_IMAGE_FILE = "/testimage.tar";
    private static final String TEST_CONTAINER_NAME = "testContainerName";
    private static final String TEST_CONTAINER_GROUP = "test-group";
    private static final String TEST_ENV_PROP = "TEST_PROP=testValue";
    private static final String[] TEST_ENV = { TEST_ENV_PROP };
    private static final String TEST_RESTART_POLICY_NAME = "on-failure";
    private static final int TEST_RESTART_POLICY_RETRIES = 15;

    private String parentComputeStateLink;
    private URI containerStateReference;
    private URI dockerAdapterServiceUri;
    private String testDockerCredentialsLink;
    private String provisioningTaskLink;
    private String containerDescriptionLink;
    private ContainerState containerState;
    private ContainerStats containerStats;
    private String containerId;
    private static URI imageReference;

    private DockerAdapterCommandExecutor commandExecutor;
    private DockerAdapterCommandExecutor failingCommandExecutor;
    private DockerAdapterService dockerAdapterService;

    @Before
    public void startServices() throws Throwable {
        HostInitTestDcpServicesConfig.startServices(host);
        HostInitPhotonModelServiceConfig.startServices(host);
        HostInitCommonServiceConfig.startServices(host);
        HostInitComputeServicesConfig.startServices(host);

        host.startService(Operation.createPost(UriUtils.buildUri(host,
                MockTaskFactoryService.SELF_LINK)),
                new MockTaskFactoryService());

        // set up a service that serves a docker image tar file from the filesystem.
        // note that to work with an external docker server the IP must be the external
        // one (not localhost)
        URL testImageResource = DockerAdapterServiceTest.class.getResource(TEST_IMAGE_FILE);
        assertNotNull("Missing test resource: " + TEST_IMAGE_FILE, testImageResource);
        File file = new File(testImageResource.toURI());
        imageReference = UriUtils.buildPublicUri(host, TEST_IMAGE_URL_PATH);
        host.startService(Operation.createPost(imageReference),
                new FileContentService(file));
    }

    @Before
    public void setupContainerState() throws Throwable {
        createTestDockerAuthCredentials();
        createParentComputeState();
        createContainerDescription();
        createContainerState();

        setupDockerAdapterService();

        createContainer();
    }

    @After
    public void tearDownContainerState() throws Throwable {
        DeploymentProfileConfig.getInstance().setTest(true);
        removeContainer();
        deleteParentComputeState();
    }

    @Test
    public void testUpdateHealthStatusOnStatsFailure() throws Throwable {
        setupDockerServiceForFailingStats();

        sendFetchContainerStatsRequest();

        waitFor(() -> {
            ContainerStats containerStats = getContainerStats(containerStateReference);
            if (containerStats.healthCheckSuccess == null) {
                return false;
            }
            return containerStats.healthCheckSuccess == false
                    && containerStats.healthFailureCount == 1;
        });

        waitFor(() -> {
            ContainerState containerWithError = getDocument(ContainerState.class,
                    containerStateReference.getPath());
            if (containerWithError.status == null) {
                return false;
            }
            return containerWithError.status.equals(ContainerState.CONTAINER_DEGRADED_STATUS);
        });

        // Degrade the container another time
        sendFetchContainerStatsRequest();

        waitFor(() -> {
            ContainerStats containerStats = getContainerStats(containerStateReference);
            return containerStats.healthCheckSuccess == false
                    && containerStats.healthFailureCount == 2;
        });

        // Degrade the container another time - should become error
        sendFetchContainerStatsRequest();
        waitFor(() -> {
            ContainerStats containerStats = getContainerStats(containerStateReference);
            boolean condition = containerStats.healthCheckSuccess == false
                    && containerStats.healthFailureCount == ContainerHealthEvaluator.DEFAULT_UNHEALTHY_THRESHOLD;

            if (!condition) {
                return false;
            }

            ContainerState errorContainerState = getDocument(ContainerState.class,
                    containerStateReference.getPath());

            if (!errorContainerState.status.equals(ContainerState.CONTAINER_ERROR_STATUS)) {
                return false;
            }

            return PowerState.ERROR == errorContainerState.powerState;
        });
    }

    private ContainerStats getContainerStats(URI containerStateReference) throws Throwable {
        ServiceStats serviceStats = getDocument(ServiceStats.class,
                containerStateReference.getPath() + ServiceHost.SERVICE_URI_SUFFIX_STATS);
        return ContainerStats.transform(serviceStats);
    }

    private void setupDockerServiceForFailingStats() throws Throwable {
        host.stopService(dockerAdapterService);

        dockerAdapterService = new DockerAdapterService() {
            @Override
            protected DockerAdapterCommandExecutor getApiCommandExecutor() {
                return getCommandExecutorWithFailingStats();
            }
        };

        host.startService(Operation.createPost(dockerAdapterServiceUri),
                dockerAdapterService);
        waitForServiceAvailability(dockerAdapterServiceUri.getPath());

    }

    private DockerAdapterCommandExecutor getCommandExecutorWithFailingStats() {
        if (failingCommandExecutor == null) {
            String serverTrust = getDockerServerTrust().certificate;
            TrustManager testTrustManager = null;

            if (serverTrust != null && !serverTrust.isEmpty()) {
                testTrustManager = CertificateUtil.getTrustManagers("docker",
                        getDockerServerTrust().certificate)[0];
            }

            failingCommandExecutor = new RemoteApiDockerAdapterCommandExecutorImpl(host,
                    testTrustManager) {

                @Override
                protected void prepareRequest(Operation op, boolean longRunningRequest) {
                    // modify the stats path which is different in the mock docker server
                    if (isMockTarget()) {
                        URI uri = op.getUri();
                        String path = uri.getRawPath();
                        if (path.endsWith("/stats")) {
                            String newPath = path.replace("/stats",
                                    MockDockerPathConstants.STATS);
                            @SuppressWarnings("unchecked")
                            Map<String, String> props = op.getBody(Map.class);
                            props.put("fail", "true");
                            op.setBody(props);

                            try {
                                URI newUri = new URI(uri.getScheme(), uri.getUserInfo(),
                                        uri.getHost(), uri.getPort(), newPath, uri.getQuery(),
                                        uri.getFragment());

                                op.setUri(newUri);

                            } catch (URISyntaxException x) {
                                throw new RuntimeException(x);
                            }
                        }
                    }

                    super.prepareRequest(op, longRunningRequest);
                }

            };
        }

        return failingCommandExecutor;

    }

    protected void createProvisioningTask() throws Throwable {
        MockTaskState provisioningTask = new MockTaskState();
        provisioningTaskLink = doPost(provisioningTask,
                MockTaskFactoryService.SELF_LINK).documentSelfLink;
    }

    protected void createContainerDescription() throws Throwable {
        createContainerDescription("busybox:latest");
    }

    protected void createContainerDescription(String image) throws Throwable {
        ContainerDescription containerDescription = new ContainerDescription();

        containerDescription.image = image;

        // use a blocking command so the container doesn't terminate immediately
        containerDescription.command = new String[] { "cat" };

        PortBinding portBinding = new PortBinding();
        portBinding.protocol = "tcp";
        portBinding.containerPort = "8080";
        portBinding.hostIp = "0.0.0.0";
        portBinding.hostPort = "9999";
        containerDescription.portBindings = new PortBinding[] { portBinding };

        containerDescription.env = TEST_ENV;

        containerDescription.restartPolicy = TEST_RESTART_POLICY_NAME;
        containerDescription.maximumRetryCount = TEST_RESTART_POLICY_RETRIES;

        containerDescriptionLink = doPost(containerDescription,
                ContainerDescriptionService.FACTORY_LINK).documentSelfLink;
    }

    protected void createContainerState() throws Throwable {
        waitForServiceAvailability(ContainerFactoryService.SELF_LINK);

        ContainerState containerState = new ContainerState();
        assertNotNull("parentLink", parentComputeStateLink);
        containerState.parentLink = parentComputeStateLink;
        containerState.descriptionLink = containerDescriptionLink;
        containerState.names = new ArrayList<>(1);
        containerState.names.add(TEST_CONTAINER_NAME);
        List<String> tenantLinks = new ArrayList<String>();
        tenantLinks.add(TEST_CONTAINER_GROUP);
        containerState.tenantLinks = tenantLinks;
        containerState.env = TEST_ENV;

        // add a custom property
        containerState.customProperties = new HashMap<>();
        containerState.customProperties.put(TEST_CUSTOM_PROP_NAME,
                TEST_CUSTOM_PROP_VALUE);

        ContainerState container = doPost(containerState, ContainerFactoryService.SELF_LINK);
        containerStateReference = UriUtils.extendUri(host.getUri(), container.documentSelfLink);
    }

    protected void createTestDockerAuthCredentials() throws Throwable {
        testDockerCredentialsLink = doPost(getDockerCredentials(),
                AuthCredentialsService.FACTORY_LINK).documentSelfLink;
        SslTrustCertificateState dockerServerTrust = getDockerServerTrust();
        if (dockerServerTrust != null && dockerServerTrust.certificate != null
                && !dockerServerTrust.certificate.isEmpty()) {
            doPost(dockerServerTrust, SslTrustCertificateService.FACTORY_LINK);
        }
    }

    protected void createParentComputeState() throws Throwable {
        waitForServiceAvailability(ComputeService.FACTORY_LINK);

        ComputeDescription computeDescription = new ComputeDescription();
        computeDescription.customProperties = new HashMap<String, String>();
        computeDescription.id = UUID.randomUUID().toString();

        String computeDescriptionLink = doPost(computeDescription,
                ComputeDescriptionService.FACTORY_LINK).documentSelfLink;

        ComputeState computeState = new ComputeState();
        computeState.id = "testParentComputeState";
        computeState.descriptionLink = computeDescriptionLink;
        computeState.customProperties = new HashMap<String, String>();
        computeState.customProperties.put(
                ComputeConstants.HOST_AUTH_CREDNTIALS_PROP_NAME,
                testDockerCredentialsLink);
        computeState.address = dockerUri.getHost();
        computeState.customProperties.put(
                ContainerHostService.DOCKER_HOST_SCHEME_PROP_NAME,
                String.valueOf(dockerUri.getScheme()));
        computeState.customProperties.put(
                ContainerHostService.DOCKER_HOST_PORT_PROP_NAME,
                String.valueOf(dockerUri.getPort()));
        computeState.customProperties.put(
                ContainerHostService.DOCKER_HOST_PATH_PROP_NAME,
                String.valueOf(dockerUri.getPath()));
        computeState.customProperties.put(
                ContainerHostService.HOST_DOCKER_ADAPTER_TYPE_PROP_NAME,
                ContainerHostService.DockerAdapterType.API.name());

        computeState = doPost(computeState, ComputeService.FACTORY_LINK);

        this.parentComputeStateLink = computeState.documentSelfLink;
    }

    protected void deleteParentComputeState() throws Throwable {
        doDelete(UriUtils.buildUri(host, parentComputeStateLink), false);
    }

    public void setupDockerAdapterService() {
        dockerAdapterServiceUri = UriUtils.buildUri(host, DockerAdapterService.class);

        dockerAdapterService = new DockerAdapterService() {
            @Override
            protected DockerAdapterCommandExecutor getApiCommandExecutor() {
                return getTestCommandExecutor();
            }
        };

        host.startService(Operation.createPost(dockerAdapterServiceUri),
                dockerAdapterService);

    }

    /**
     * Test create and delete container requests to the DockerAdapterService
     *
     * @throws Throwable
     */
    @Test
    public void testCreateDelete() throws Throwable {
        verifyContainerListContainsId(containerId);

        String customPropValue = containerState.customProperties
                .get(TEST_CUSTOM_PROP_NAME);
        assertEquals("Unexpected custom property value in ContainerState",
                TEST_CUSTOM_PROP_VALUE, customPropValue);

        // verify the property value in the actual container (under the "Config"
        // object)
        verifyContainerProperty(TEST_CUSTOM_PROP_PATH, TEST_CUSTOM_PROP_VALUE);

        assertEquals("Expected container to be running", PowerState.RUNNING,
                containerState.powerState);

        assertNotNull("Name is missing", containerState.names);
        assertEquals("Name", TEST_CONTAINER_NAME, containerState.names.get(0));
        assertNotNull("Created date is null", containerState.created);
        assertNotNull("Started date is null", containerState.started);
        assertNotNull("Ports is null", containerState.ports);

        Map<String, String> expectedPortMapping = new HashMap<>();
        expectedPortMapping.put("HostIp", "0.0.0.0");
        expectedPortMapping.put("HostPort", "9999");
        List<Map<String, String>> expectedPortMappings = Collections
                .singletonList(expectedPortMapping);

        verifyContainerProperty("HostConfig.PortBindings.8080/tcp", expectedPortMappings);
        // verifyContainerProperty("NetworkSettings.Ports.8080/tcp", expectedPortMappings);
        verifyContainerProperty("Config.Env", Arrays.asList(TEST_ENV));
        verifyContainerProperty("HostConfig.RestartPolicy.Name", TEST_RESTART_POLICY_NAME);
        verifyContainerProperty("HostConfig.RestartPolicy.MaximumRetryCount",
                TEST_RESTART_POLICY_RETRIES);

        assertEquals("Unexpected number of port bindings", 1, containerState.ports.size());
        PortBinding portBinding = containerState.ports.get(0);

        assertEquals("Container port", "8080", portBinding.containerPort);
        assertEquals("Host IP", expectedPortMapping.get("HostIp"), portBinding.hostIp);
        assertEquals("Host port", expectedPortMapping.get("HostPort"), portBinding.hostPort);
        assertEquals("Protocol", Protocol.TCP.toString(), portBinding.protocol);
    }

    @Test
    public void testCreateDeleteAgainstSecureRegistry() throws Throwable {
        // prepare environment
        mockDockerCreateImageService.setExpectRegistryAuthHeader(true);

        AuthCredentialsServiceState authState = new AuthCredentialsServiceState();
        authState.type = AuthCredentialsType.Password.toString();
        authState.userEmail = REGISTRY_USER;
        authState.privateKey = REGISTRY_PASSWORD;
        String authStateLink = doPost(authState,
                AuthCredentialsService.FACTORY_LINK).documentSelfLink;

        RegistryState registryState = new RegistryState();
        registryState.authCredentialsLink = authStateLink;
        registryState.address = "https://test.registry.com:5000";
        registryState.name = "test registry";
        doPost(registryState, RegistryService.FACTORY_LINK);

        createContainerDescription("test.registry.com:5000/test/busybox:latest");
        createContainerState();
        createContainer();

        // verify
        verifyContainerListContainsId(containerId);

        String customPropValue = containerState.customProperties
                .get(TEST_CUSTOM_PROP_NAME);
        assertEquals("Unexpected custom property value in ContainerState",
                TEST_CUSTOM_PROP_VALUE, customPropValue);

        // verify the property value in the actual container (under the "Config"
        // object)
        verifyContainerProperty(TEST_CUSTOM_PROP_PATH, TEST_CUSTOM_PROP_VALUE);

        assertEquals("Expected container to be running", PowerState.RUNNING,
                containerState.powerState);

        assertNotNull("Name is missing", containerState.names);
        assertEquals("Name", TEST_CONTAINER_NAME, containerState.names.get(0));
        assertNotNull("Created date is null", containerState.created);
        assertNotNull("Started date is null", containerState.started);
        assertNotNull("Ports is null", containerState.ports);

        Map<String, String> expectedPortMapping = new HashMap<>();
        expectedPortMapping.put("HostIp", "0.0.0.0");
        expectedPortMapping.put("HostPort", "9999");
        List<Map<String, String>> expectedPortMappings = Collections
                .singletonList(expectedPortMapping);

        verifyContainerProperty("HostConfig.PortBindings.8080/tcp", expectedPortMappings);
        // verifyContainerProperty("NetworkSettings.Ports.8080/tcp", expectedPortMappings);
        verifyContainerProperty("Config.Env", Arrays.asList(TEST_ENV));
        verifyContainerProperty("HostConfig.RestartPolicy.Name", TEST_RESTART_POLICY_NAME);
        verifyContainerProperty("HostConfig.RestartPolicy.MaximumRetryCount",
                TEST_RESTART_POLICY_RETRIES);

        assertEquals("Unexpected number of port bindings", 1, containerState.ports.size());
        PortBinding portBinding = containerState.ports.get(0);

        assertEquals("Container port", "8080", portBinding.containerPort);
        assertEquals("Host IP", expectedPortMapping.get("HostIp"), portBinding.hostIp);
        assertEquals("Host port", expectedPortMapping.get("HostPort"), portBinding.hostPort);
        assertEquals("Protocol", Protocol.TCP.toString(), portBinding.protocol);
    }

    /**
     * Test stop and start container requests to the DockerAdapterService
     *
     * @throws Throwable
     */
    @Test
    public void testStopAndStart() throws Throwable {
        // verify container is running
        verifyContainerIsRunning(true);
        assertEquals("Unexpected PowerState in ContainerState",
                PowerState.RUNNING, containerState.powerState);

        sendStopContainerRequest();

        // wait for provisioning task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        // verify container is stopped
        verifyContainerIsRunning(false);
        sendGetContainerStateRequest();
        assertEquals("Unexpected PowerState in ContainerState",
                PowerState.STOPPED, containerState.powerState);

        sendStartContainerRequest();

        // wait for provisioning task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        // verify container is running again
        verifyContainerIsRunning(true);
        sendGetContainerStateRequest();
        assertEquals("Unexpected PowerState in ContainerState",
                PowerState.RUNNING, containerState.powerState);
    }

    @Test
    public void testFetchContainerStats() throws Throwable {
        sendFetchContainerStatsRequest();

        // wait for request task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        sendGetContainerStatsStateRequest();
        assertNotNull("cpu_stats is missing", containerStats.cpuUsage > 0);
    }

    @Test
    public void testStatsDisabledWhenContainerStopped() throws Throwable {
        sendFetchContainerStatsRequest();

        // wait for request task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        // stop the container
        sendStopContainerRequest();

        // wait for request task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        // try to fetch the stats again and expect failure
        sendFetchContainerStatsRequest();

        // wait for request task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FAILED);
    }

    // jira issue VSYM-222 - Delete container fails with error
    @Test
    public void testRemoveMissingContainer() throws Throwable {
        removeContainer();
        // when we try to delete container that is already deleted the operation should not fail
        removeContainer();
    }

    /**
     * Create a container, store its id in this.containerId
     *
     * @throws Throwable
     */
    protected void createContainer() throws Throwable {
        sendCreateContainerRequest();

        // wait for provisioning task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        verifyContainerStateExists(containerStateReference);

        // get and validate the container state
        sendGetContainerStateRequest();
        containerId = containerState.id;
        assertNotNull("Container ID is null", containerId);
        assertEquals("container id should be 64 characters long: "
                + containerId, 64, containerId.length());
    }

    /**
     * Remove the container previously created using createContainer()
     *
     * @throws Throwable
     */
    protected void removeContainer() throws Throwable {
        if (containerId == null) {
            return;
        }

        // send a delete request
        sendDeleteContainerRequest();

        // wait for provisioning task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, "taskInfo.stage",
                TaskState.TaskStage.FINISHED);

        if (!isMockTarget()) {
            // removing the container actually takes some time when using a real docker server
            Thread.sleep(2000L);
        }

        // verify container is removed by issuing an inspect command
        verifyContainerDoesNotExist(containerId);
    }

    private void sendCreateContainerRequest() throws Throwable {
        sendContainerRequest(ContainerOperationType.CREATE);
    }

    private void sendDeleteContainerRequest() throws Throwable {
        sendContainerRequest(ContainerOperationType.DELETE);
    }

    private void sendStopContainerRequest() throws Throwable {
        sendContainerRequest(ContainerOperationType.STOP);
    }

    private void sendStartContainerRequest() throws Throwable {
        sendContainerRequest(ContainerOperationType.START);
    }

    private void sendFetchContainerStatsRequest() throws Throwable {
        sendContainerRequest(ContainerOperationType.STATS);
    }

    private void sendContainerRequest(ContainerOperationType type)
            throws Throwable {

        // create a fresh provisioning task for each request
        createProvisioningTask();

        ContainerInstanceRequest request = new ContainerInstanceRequest();
        request.resourceReference = containerStateReference;
        request.operationTypeId = type.id;
        request.serviceTaskCallback = ServiceTaskCallback.create(provisioningTaskLink);

        Operation startContainer = Operation
                .createPatch(dockerAdapterServiceUri)
                .setReferer(URI.create("/")).setBody(request)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        host.failIteration(ex);
                    }

                    host.completeIteration();
                });

        host.testStart(1);
        host.send(startContainer);
        host.testWait();

        if (!isMockTarget()) {
            // in case of testing with a real docker server, give it some time to settle
            Thread.sleep(2000L);
        }
    }

    private void sendGetContainerStateRequest() throws Throwable {
        Operation getContainerState = Operation.createGet(containerStateReference)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        host.failIteration(ex);

                    } else {
                        containerState = o.getBody(ContainerState.class);
                        host.completeIteration();
                    }
                });

        host.testStart(1);
        host.send(getContainerState);
        host.testWait();
    }

    private void sendGetContainerStatsStateRequest() throws Throwable {
        host.testStart(1);
        host.send(Operation.createGet(UriUtils.buildStatsUri(containerStateReference))
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        host.failIteration(ex);
                    } else {
                        ServiceStats serviceStats = o.getBody(ServiceStats.class);
                        containerStats = ContainerStats.transform(serviceStats);
                        host.completeIteration();
                    }
                }));
        host.testWait();
    }

    /*
     * Perform a query for the document self link to see that it exists in the index
     */
    protected void verifyContainerStateExists(URI containerStateReference)
            throws Throwable {

        host.createAndWaitSimpleDirectQuery(
                ServiceDocument.FIELD_NAME_SELF_LINK, containerStateReference.getPath(), 0, 1);
    }

    /*
     * Perform an inspect command an expect to get a 404 back from the api
     */
    protected void verifyContainerDoesNotExist(String containerId)
            throws Throwable {
        CommandInput commandInput = new CommandInput()
                .withDockerUri(getDockerVersionedUri())
                .withCredentials(getDockerCredentials())
                .withProperty(
                        DockerAdapterCommandExecutor.DOCKER_CONTAINER_ID_PROP_NAME, containerId);

        host.testStart(1);
        getTestCommandExecutor().inspectContainer(
                commandInput,
                (o, ex) -> {
                    if (ex != null) {
                        if (o.getStatusCode() == Operation.STATUS_CODE_NOT_FOUND) {
                            /* this is the expected result - the container is not found */
                            host.completeIteration();
                        } else {
                            /* some other unexpected exception */
                            host.failIteration(ex);
                        }
                    } else {
                        host.failIteration(new AssertionError(
                                "Expected container not to be found, but it was: " + containerId));
                    }
                });

        host.testWait();
    }

    /**
     * Perform an inspect command to check the container's run status
     *
     * @param expectedState
     * @throws Throwable
     */
    protected void verifyContainerIsRunning(final boolean expectedState)
            throws Throwable {

        verifyContainerProperty("State.Running", expectedState);
    }

    /**
     * Perform an inspect command to check the container's run status
     *
     * @throws Throwable
     */
    protected void verifyContainerProperty(String propertyPath,
            Object expectedValue) throws Throwable {

        CommandInput commandInput = new CommandInput()
                .withDockerUri(getDockerVersionedUri())
                .withCredentials(getDockerCredentials())
                .withProperty(
                        DockerAdapterCommandExecutor.DOCKER_CONTAINER_ID_PROP_NAME,
                        containerId);

        host.testStart(1);
        getTestCommandExecutor().inspectContainer(commandInput, (o, ex) -> {
            if (ex != null) {
                host.failIteration(ex);
            } else {
                Object body = o.getBody(Map.class);
                host.log(Level.INFO, "Retrieved container properties: %s", body);
                Object value = getNestedPropertyByPath(body, propertyPath);
                try {
                    if (value instanceof Number) {
                        double expectedAsDouble = ((Number) expectedValue).doubleValue();
                        double actualAsDouble = ((Number) value).doubleValue();
                        assertEquals(String.format(
                                "unexpected container property value for '%s'",
                                propertyPath), expectedAsDouble, actualAsDouble, 0.00001);

                    } else {
                        assertEquals(
                                String.format(
                                        "unexpected container property value for '%s'",
                                        propertyPath),
                                expectedValue, value);
                    }
                    host.completeIteration();
                } catch (Throwable x) {
                    host.failIteration(x);
                }
            }
        });
        host.testWait();
    }

    protected void verifyContainerListContainsId(String containerId) throws Throwable {

        CommandInput commandInput = new CommandInput()
                .withDockerUri(getDockerVersionedUri())
                .withCredentials(getDockerCredentials());

        host.testStart(1);
        getTestCommandExecutor().listContainers(commandInput, (o, ex) -> {
            if (ex != null) {
                host.failIteration(ex);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> body = o.getBody(List.class);
                host.log(Level.INFO, "Retrieved container list: %s", body);

                Set<Object> containerIds = body.stream()
                        .map((item) -> item.get("Id")).collect(Collectors.toSet());

                try {
                    assertTrue(String.format("Container ID [%s] not found in container list: %s ",
                            containerId, containerIds), containerIds.contains(containerId));
                    host.completeIteration();
                } catch (Throwable x) {
                    host.failIteration(x);
                }

            }
        });
        host.testWait();
    }

    /**
     * Get a property designated by a dot-notation path from a root object
     *
     * This implementation assumes the object is a nested map of maps until the leaf object is found
     *
     * @param object
     * @param propertyPath
     * @return
     */
    private Object getNestedPropertyByPath(Object object, String propertyPath) {
        List<String> pathParts = new LinkedList<>(Arrays.asList(propertyPath
                .split("\\.")));

        while ((object instanceof Map) && (!pathParts.isEmpty())) {
            String pathPart = pathParts.remove(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) object;
            object = map.get(pathPart);
        }

        return object;
    }

    private DockerAdapterCommandExecutor getTestCommandExecutor() {
        if (commandExecutor == null) {
            String serverTrust = getDockerServerTrust().certificate;
            TrustManager testTrustManager = null;

            if (serverTrust != null && !serverTrust.isEmpty()) {
                testTrustManager = CertificateUtil.getTrustManagers("docker",
                        getDockerServerTrust().certificate)[0];
            }

            commandExecutor = new RemoteApiDockerAdapterCommandExecutorImpl(host,
                    testTrustManager) {

                @Override
                protected void prepareRequest(Operation op, boolean longRunningRequest) {
                    // modify the stats path which is different in the mock docker server
                    if (isMockTarget()) {
                        URI uri = op.getUri();
                        String path = uri.getRawPath();
                        if (path.endsWith("/stats")) {
                            String newPath = path.replace("/stats",
                                    MockDockerPathConstants.STATS);

                            try {
                                URI newUri = new URI(uri.getScheme(), uri.getUserInfo(),
                                        uri.getHost(), uri.getPort(), newPath, uri.getQuery(),
                                        uri.getFragment());

                                op.setUri(newUri);

                            } catch (URISyntaxException x) {
                                throw new RuntimeException(x);
                            }
                        }
                    }

                    super.prepareRequest(op, longRunningRequest);
                }

            };
        }

        return commandExecutor;
    }

}
