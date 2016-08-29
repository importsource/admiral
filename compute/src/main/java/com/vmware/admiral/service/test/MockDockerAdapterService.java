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

package com.vmware.admiral.service.test;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.vmware.admiral.adapter.common.AdapterRequest;
import com.vmware.admiral.adapter.common.ContainerOperationType;
import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.compute.container.ContainerService.ContainerState.PowerState;
import com.vmware.admiral.compute.container.PortBinding;
import com.vmware.admiral.compute.container.ShellContainerExecutorService;
import com.vmware.admiral.compute.container.maintenance.ContainerStats;
import com.vmware.admiral.compute.container.maintenance.ContainerStatsEvaluator;
import com.vmware.admiral.service.common.LogService;
import com.vmware.admiral.service.common.ServiceTaskCallback.ServiceTaskCallbackResponse;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.ServiceHostLogService;

/**
 * Mock Docker Adapter service to be used in unit and integration tests.
 */
public class MockDockerAdapterService extends StatelessService {
    public static final String SELF_LINK = ManagementUriParts.ADAPTER_DOCKER;

    public static final String MOCK_CURRENT_EXECUTED_OPERATION_KEY = "MOCK_CURRENT_EXECUTED_OPERATION_KEY";
    public static final String FAILURE_EXPECTED = "FAILURE_EXPECTED";
    public static final String MOCK_HOST_ASSIGNED_ADDRESS = "192.168.1.129";

    public boolean isFailureExpected;
    public String computeHostIpAddress = MOCK_HOST_ASSIGNED_ADDRESS;

    // Map of container ids by hostId. hostId -> Map of containerId -> containerReference
    private static final Map<String, Map<String, String>> CONTAINER_IDS = new ConcurrentHashMap<>();
    // Map of container ids and names by hostId. hostId -> Map of containerId -> container name
    private static final Map<String, Map<String, String>> CONTAINER_IDS_AND_NAMES = new ConcurrentHashMap<>();
    // Map of container ids and image by hostId. hostId -> Map of containerId -> container image
    private static final Map<String, Map<String, String>> CONTAINER_IDS_AND_IMAGE = new ConcurrentHashMap<>();

    private static class MockAdapterRequest extends AdapterRequest {

        public boolean isProvisioning() {
            return ContainerOperationType.CREATE.id.equals(operationTypeId);
        }

        public boolean isDeprovisioning() {
            return ContainerOperationType.DELETE.id.equals(operationTypeId);
        }

        public TaskState validateMock() {
            TaskState taskInfo = new TaskState();
            try {
                validate();
            } catch (Exception e) {
                taskInfo.stage = TaskStage.FAILED;
                taskInfo.failure = Utils.toServiceErrorResponse(e);
            }

            return taskInfo;
        }
    }

    @Override
    public void handleRequest(Operation op) {
        if (op.getAction() == Action.DELETE) {
            if (ServiceHost.isServiceStop(op)) {
                handleDeleteCompletion(op);
                return;
            }
            if (op.hasBody()) {
                MockAdapterRequest state = op.getBody(MockAdapterRequest.class);
                removeContainerIdByReference(state.resourceReference);
                op.complete();
                return;
            } else {
                op.complete();
                return;
            }
        }

        if (op.getAction() == Action.GET) {
            op.setStatusCode(204);
            op.complete();
            return;
        }

        if (op.getAction() != Action.PATCH) {
            op.fail(new IllegalArgumentException("action not supported"));
            return;
        }

        if (op.getReferer().getPath().equals(ShellContainerExecutorService.SELF_LINK)) {
            op.setBody("");
        }

        op.setStatusCode(Operation.STATUS_CODE_ACCEPTED).complete();

        MockAdapterRequest state = op.getBody(MockAdapterRequest.class);

        TaskState taskInfo = state.validateMock();

        logInfo("Request accepted for resource: %s", state.resourceReference);
        if (TaskStage.FAILED == taskInfo.stage) {
            logInfo("Failed request for resource:  %s", state.resourceReference);
            patchProvisioningTask(state, taskInfo.failure);
            return;
        }

        // static way to define expected failure
        if (this.isFailureExpected) {
            logInfo("Expected failure request for resource:  %s", state.resourceReference);
            patchProvisioningTask(state, new IllegalStateException("Simulated failure"));
            return;
        }

        // define expected failure dynamically for every request
        if (state.customProperties != null
                && state.customProperties.containsKey(FAILURE_EXPECTED)) {
            logInfo("Expected failure request from custom props for resource:  %s",
                    state.resourceReference);
            patchProvisioningTask(state, new IllegalStateException("Simulated failure"));
            return;
        }

        processContainerRequest(state, taskInfo, null, null);
    }

    private void processContainerRequest(MockAdapterRequest state, TaskState taskInfo,
            ContainerState containerState, ContainerDescription contDesc) {
        if (TaskStage.FAILED == taskInfo.stage) {
            logInfo("Failed request based on containerState resource:  %s",
                    state.resourceReference);
            patchProvisioningTask(state, taskInfo.failure);
            return;
        }

        if (containerState == null) {
            getDocument(ContainerState.class, state.resourceReference, taskInfo,
                    (container) -> processContainerRequest(state, taskInfo, container, contDesc));
            return;
        }

        // define expected failure dynamically for every request
        if (containerState.customProperties != null
                && containerState.customProperties.remove(FAILURE_EXPECTED) != null) {
            updateContainerState(containerState, () -> patchProvisioningTask(state,
                    new IllegalStateException("Simulated failure")));
            return;
        }

        if (contDesc == null && !state.isDeprovisioning()) {
            getDocument(ContainerDescription.class,
                    UriUtils.buildUri(getHost(), containerState.descriptionLink), taskInfo,
                    (desc) -> processContainerRequest(state, taskInfo, containerState, desc));
            return;
        }

        if (state.isProvisioning()) {
            patchContainerStateWithIPAddress(state, containerState, contDesc);
        } else if (state.isDeprovisioning()) {
            removeContainerIdByReference(state.resourceReference);
            patchProvisioningTask(state, (Throwable) null);
        } else if (ContainerOperationType.INSPECT.id.equals(state.operationTypeId)) {
            patchContainerInspect(state, containerState);
        } else if (ContainerOperationType.STATS.id.equals(state.operationTypeId)) {
            patchContainerStats(state, containerState);
        } else if (ContainerOperationType.FETCH_LOGS.id.equals(state.operationTypeId)) {
            createLogState(state, containerState);
        } else {
            patchContainerPowerState(state, containerState, contDesc);
        }
    }

    private void patchContainerStateWithIPAddress(MockAdapterRequest state,
            ContainerState containerState, ContainerDescription containerDesc) {
        containerState.address = this.computeHostIpAddress;
        containerState.adapterManagementReference = UriUtils.buildUri(getUri(), SELF_LINK);
        containerState.powerState = PowerState.RUNNING;
        String containerId = UUID.randomUUID().toString();
        containerState.id = containerId;
        containerState.adapterManagementReference = getUri();
        containerState.created = new Date().getTime();
        containerState.command = containerDesc.command;
        containerState.image = containerDesc.image;
        containerState.status = "Started";
        containerState.documentExpirationTimeMicros = -1;
        if (containerDesc.portBindings != null && containerDesc.portBindings.length != 0) {
            List<PortBinding> portBindings = new ArrayList<>();
            for (PortBinding portBinding : containerDesc.portBindings) {
                if (portBinding.hostPort == null) {
                    // if the port binding doesn't specify a host port, bind a random port
                    int randomPort = new Random().nextInt(65536);
                    portBinding.hostPort = String.valueOf(randomPort);
                }

                portBindings.add(portBinding);
            }
            containerState.ports = portBindings;
        } else {
            PortBinding portBinding = new PortBinding();
            // if the port binding doesn't specify a host port, bind a random port
            int randomPort = new Random().nextInt(65536);
            portBinding.hostPort = String.valueOf(randomPort);
            portBinding.containerPort = String.valueOf(randomPort);
            containerState.ports = new ArrayList<>();
            containerState.ports.add(portBinding);
        }

        // Add the container by hostId
        String hostId = Service.getId(containerState.parentLink);
        String containerName = null;
        if (containerState.names != null && !containerState.names.isEmpty()) {
            containerName = containerState.names.get(0);
        }
        addContainerId(hostId, containerState.id, state.resourceReference.toString());
        addContainerNames(hostId, containerId, containerName);

        sendRequest(Operation.createPatch(state.resourceReference)
                .setBody(containerState)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                .setCompletion((o, e) -> {
                    Throwable patchException = null;
                    if (e != null) {
                        logSevere(e);
                        patchException = e;
                    }
                    patchProvisioningTask(state, patchException);
                }));
    }

    private void patchContainerPowerState(MockAdapterRequest state,
            ContainerState containerState, ContainerDescription containerDesc) {

        if (ContainerOperationType.START.id.equals(state.operationTypeId)) {
            containerState.powerState = PowerState.RUNNING;
        } else if (ContainerOperationType.STOP.id.equals(state.operationTypeId)) {
            containerState.powerState = PowerState.STOPPED;
        } else {
            if (containerState.customProperties == null) {
                containerState.customProperties = new HashMap<>();
            }
            containerState.customProperties
                    .put(MOCK_CURRENT_EXECUTED_OPERATION_KEY, state.operationTypeId);
        }

        sendRequest(Operation.createPatch(state.resourceReference)
                .setBody(containerState)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                .setCompletion((o, e) -> {
                    Throwable patchException = null;
                    if (e != null) {
                        logSevere(e);
                        patchException = e;
                    }
                    patchProvisioningTask(state, patchException);
                }));
    }

    private void patchProvisioningTask(MockAdapterRequest state, Throwable exception) {
        patchProvisioningTask(state,
                exception == null ? null : Utils.toServiceErrorResponse(exception));
    }

    private void patchProvisioningTask(MockAdapterRequest state, ServiceErrorResponse errorResponse) {
        if (state.serviceTaskCallback.isEmpty()) {
            return;
        }
        ServiceTaskCallbackResponse callbackResponse = null;
        if (errorResponse != null) {
            callbackResponse = state.serviceTaskCallback.getFailedResponse(errorResponse);
        } else {
            callbackResponse = state.serviceTaskCallback.getFinishedResponse();
        }

        URI callbackReference = URI.create(state.serviceTaskCallback.serviceSelfLink);
        if (callbackReference.getScheme() == null) {
            callbackReference = UriUtils.buildUri(getHost(),
                    state.serviceTaskCallback.serviceSelfLink);
        }

        // tell the parent we are done. We are a mock service, so we get things done, fast.
        sendRequest(Operation
                .createPatch(callbackReference)
                .setBody(callbackResponse)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        logWarning(
                                "Notifying parent task %s from mock docker adapter failed: %s",
                                o.getUri(), Utils.toString(e));
                    }
                }));
    }

    private void patchContainerInspect(MockAdapterRequest state, ContainerState container) {
        ContainerState newContainerState = new ContainerState();
        newContainerState.attributes = new HashMap<String, String>();
        newContainerState.attributes.put("Config",
                "{\"Cmd\": [\"/bin/sh\",\"-c\",\"exit 9\"], \"Hostname\": \"ba033ac44011\", \"Image\": \""
                        + container.image + "\"}");
        newContainerState.attributes.put("HostConfig",
                "{\"CpuShares\": 12, \"CpusetCpus\": 33, \"Memory\": 245, \"MemorySwap\": 123}");
        newContainerState.attributes.put("NetworkSettings", "{\"IPAddress\": \"10.23.47.89\"}");
        newContainerState.address = this.computeHostIpAddress;
        newContainerState.image = container.image;
        getHost().log(Level.FINE, "Patching ContainerState: %s ", state.resourceReference);
        sendRequest(Operation.createPatch(state.resourceReference)
                .setBody(newContainerState)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                .setCompletion((o, ex) -> {
                    Throwable patchException = null;
                    if (ex != null) {
                        logSevere(ex);
                        patchException = ex;
                    }
                    patchProvisioningTask(state, patchException);
                }));
    }

    private void createLogState(MockAdapterRequest state, ContainerState containerState) {
        String logFile = ServiceHostLogService.getDefaultProcessLogName();
        getHost().log(Level.INFO, "logFile: %s ", logFile);

        LogService.LogServiceState logServiceState = new LogService.LogServiceState();
        logServiceState.documentSelfLink = Service.getId(state.resourceReference
                .toString());
        logServiceState.tenantLinks = containerState.tenantLinks;
        if (logFile == null) {
            logServiceState.logs = "log file not found".getBytes();

        } else {
            try {
                logServiceState.logs = Files.readAllBytes((new File(logFile)).toPath());
            } catch (Exception e) {
                patchProvisioningTask(state, e);
                return;
            }
        }

        sendRequest(Operation.createPost(this, LogService.FACTORY_LINK)
                .setBody(logServiceState)
                .setCompletion((o, ex) -> {
                    Throwable patchException = null;
                    if (ex != null) {
                        logSevere(ex);
                        patchException = ex;
                    }
                    patchProvisioningTask(state, patchException);
                }));
    }

    private void patchContainerStats(MockAdapterRequest state, ContainerState containerState) {
        // CPU calculation:
        // var cpuDelta = stats.cpu_stats.cpu_usage.total_usage -
        // stats.precpu_stats.cpu_usage.total_usage;
        // var systemDelta = stats.cpu_stats.system_cpu_usage - stats.precpu_stats.system_cpu_usage;
        // cpuUsage=((cpuDelta/systemDelta) * stats.cpu_stats.cpu_usage.percpu_usage.length)*100.0;

        int max = 9;
        int min = 1;
        Random rand = new Random();
        int random = rand.nextInt((max - min) + 1) + min;
        int randomNext = rand.nextInt((max - min) + 1) + min;
        JsonObject stats = new JsonObject();
        JsonObject cpu_stats = new JsonObject();
        stats.add("cpu_stats", cpu_stats);
        cpu_stats.addProperty("system_cpu_usage", 684753090000000L * randomNext);

        JsonObject cpu_usage = new JsonObject();
        cpu_stats.add("cpu_usage", cpu_usage);
        cpu_usage.addProperty("total_usage", 257301730000000L * random);

        JsonArray percpu_usage = new JsonArray();
        percpu_usage.add(new JsonPrimitive(208092140000000L * random));
        percpu_usage.add(new JsonPrimitive(49209590000000L * random));
        cpu_usage.add("percpu_usage", percpu_usage);

        JsonObject precpu_stats = new JsonObject();
        stats.add("precpu_stats", precpu_stats);
        precpu_stats.addProperty("system_cpu_usage", 484235090000000L * randomNext);

        JsonObject precpu_usage = new JsonObject();
        precpu_stats.add("cpu_usage", precpu_usage);
        precpu_usage.addProperty("total_usage", 227305640000000L * random);

        // Memory calculation:
        // var memStats = data.memory_stats;
        // var memoryPercentage = (memStats.usage / memStats.limit) * 100;
        JsonObject memory_stats = new JsonObject();
        memory_stats.addProperty("usage", 3042080 * Math.min(random, randomNext));
        memory_stats.addProperty("limit", 5000000 * Math.max(random, randomNext));
        stats.add("memory_stats", memory_stats);

        // Network:
        // data.network.rx_bytes, data.network.tx_bytes
        JsonObject network = new JsonObject();
        network.addProperty("rx_bytes", 34887 * random);
        network.addProperty("tx_bytes", 579367 * random);
        stats.add("network", network);

        ContainerStats containerStats = ContainerStatsEvaluator
                .calculateStatsValues(stats.toString());
        containerStats.healthCheckSuccess = true;

        URI uri = UriUtils.buildUri(getHost(), containerState.documentSelfLink);
        getHost().log(Level.FINE, "Updating container stats: %s ", uri);

        sendRequest(Operation.createPatch(uri)
                .setBody(containerStats)
                .setCompletion((o, ex) -> {
                    Throwable patchException = null;
                    if (ex != null) {
                        logSevere(ex);
                        patchException = ex;
                    }
                    patchProvisioningTask(state, patchException);
                }));
    }

    private void updateContainerState(ContainerState container, Runnable callbackFunc) {
        sendRequest(Operation.createPut(this, container.documentSelfLink)
                .setBody(container)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        logSevere(ex);
                    }
                    callbackFunc.run();
                }));
    }

    @SuppressWarnings("unchecked")
    private <T> void getDocument(Class<T> type, URI reference, TaskState taskInfo,
            Consumer<T> callbackFunction) {
        final Object[] result = new Object[] { null };
        sendRequest(Operation.createGet(reference)
                .setCompletion(
                        (o, e) -> {
                            if (e != null) {
                                logSevere(e);
                                taskInfo.stage = TaskStage.FAILED;
                                taskInfo.failure = Utils.toServiceErrorResponse(e);
                            } else {
                                result[0] = o.getBody(type);
                                if (result[0] != null) {
                                    logInfo("Get Document: [%s]", reference);
                                    taskInfo.stage = TaskStage.FINISHED;
                                } else {
                                    String errMsg = String.format("Can't find resource: [%s]",
                                            reference);
                                    logSevere(errMsg);
                                    taskInfo.stage = TaskStage.FAILED;
                                    taskInfo.failure = Utils
                                            .toServiceErrorResponse(new IllegalStateException(
                                                    errMsg));
                                }
                            }
                            callbackFunction.accept((T) result[0]);
                        }));

    }

    public static synchronized void resetContainers() {
        CONTAINER_IDS.clear();
        CONTAINER_IDS_AND_NAMES.clear();
        CONTAINER_IDS_AND_IMAGE.clear();
    }

    public static synchronized int getNumberOfContainers() {
        return getContainerIds().size();
    }

    public static synchronized void removeContainerId(String containerId) {
        Iterator<Map<String, String>> iteratorHost = CONTAINER_IDS.values().iterator();
        while (iteratorHost.hasNext()) {
            Map<String, String> containerIdsByHost = iteratorHost.next();
            if (containerIdsByHost.containsKey(containerId)) {
                containerIdsByHost.remove(containerId);
                Utils.log(MockDockerAdapterService.class,
                        MockDockerAdapterService.class.getSimpleName(),
                        Level.INFO, "Container with id: %s removed.", containerId);
                return;
            }

        }
    }

    private synchronized void removeContainerIdByReference(URI containerReference) {
        String ref = containerReference.toString();
        Iterator<Map<String, String>> iteratorHost = CONTAINER_IDS.values().iterator();
        while (iteratorHost.hasNext()) {
            Map<String, String> containerIdsByHost = iteratorHost.next();
            Iterator<Entry<String, String>> iterator = containerIdsByHost.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<String, String> entry = iterator.next();
                if (ref.equals(entry.getValue())) {
                    Utils.log(MockDockerAdapterService.class,
                            MockDockerAdapterService.class.getSimpleName(),
                            Level.INFO, "Container with id: %s and container ref: %s removed.",
                            entry.getKey(), containerReference);
                    iterator.remove();
                    return;
                }
            }
        }
        Utils.logWarning("**************** No containerId found for reference: " + ref);
    }

    public static synchronized void addContainerId(String hostId, String containerId,
            String containerReference) {
        Utils.log(MockDockerAdapterService.class, MockDockerAdapterService.class.getSimpleName(),
                Level.INFO, "Container with id: %s and container ref: %s created in host: %s.",
                containerId, containerReference, hostId);
        if (!CONTAINER_IDS.containsKey(hostId)) {
            CONTAINER_IDS.put(hostId, new ConcurrentHashMap<>());
        }
        CONTAINER_IDS.get(hostId).put(containerId, containerReference);
    }

    public static synchronized Set<String> getContainerIds() {
        Set<String> containerIds = new HashSet<>();
        Iterator<Map<String, String>> iteratorHost = CONTAINER_IDS.values().iterator();
        while (iteratorHost.hasNext()) {
            Map<String, String> containerIdsByHost = iteratorHost.next();
            containerIds.addAll(containerIdsByHost.keySet());
        }
        return containerIds;
    }

    public static synchronized Set<String> getContainerIds(String hostId) {
        if (CONTAINER_IDS.containsKey(hostId)) {
            return CONTAINER_IDS.get(hostId).keySet();
        } else {
            return Collections.emptySet();
        }
    }

    public static synchronized boolean isContainerProvisioned(String containerLink) {
        for (String containerRef : getContainerIdsWithContainerReferences().values()) {
            if (containerRef.endsWith(containerLink)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized Map<String, String> getContainerIdsWithContainerReferences() {
        Map<String, String> containerIdWithRefs = new HashMap<>();
        Iterator<Map<String, String>> iteratorHost = CONTAINER_IDS.values().iterator();
        while (iteratorHost.hasNext()) {
            Map<String, String> containerIdsByHost = iteratorHost.next();
            containerIdWithRefs.putAll(containerIdsByHost);
        }
        return containerIdWithRefs;
    }

    public static synchronized String getContainerNames(String containerId) {
        Iterator<Map<String, String>> iteratorHost = CONTAINER_IDS_AND_NAMES.values().iterator();
        while (iteratorHost.hasNext()) {
            Map<String, String> containerIdsAndNamesByHost = iteratorHost.next();
            if (containerIdsAndNamesByHost.containsKey(containerId)) {
                return containerIdsAndNamesByHost.get(containerId);
            }
        }
        return null;
    }

    public static synchronized void addContainerNames(String hostId, String containerId,
            String names) {
        if (!CONTAINER_IDS_AND_NAMES.containsKey(hostId)) {
            CONTAINER_IDS_AND_NAMES.put(hostId, new ConcurrentHashMap<>());
        }
        CONTAINER_IDS_AND_NAMES.get(hostId).put(containerId, names);
    }

    public static synchronized String getContainerImage(String containerId) {
        Iterator<Map<String, String>> iteratorHost = CONTAINER_IDS_AND_IMAGE.values().iterator();
        while (iteratorHost.hasNext()) {
            Map<String, String> containerIdsAndImageByHost = iteratorHost.next();
            if (containerIdsAndImageByHost.containsKey(containerId)) {
                return containerIdsAndImageByHost.get(containerId);
            }
        }
        return null;
    }

    public static synchronized void addContainerImage(String hostId, String containerId,
            String image) {
        if (!CONTAINER_IDS_AND_IMAGE.containsKey(hostId)) {
            CONTAINER_IDS_AND_IMAGE.put(hostId, new ConcurrentHashMap<>());
        }
        CONTAINER_IDS_AND_IMAGE.get(hostId).put(containerId, image);
    }
}