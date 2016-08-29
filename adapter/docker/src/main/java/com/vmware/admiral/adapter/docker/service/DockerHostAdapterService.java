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

import static com.vmware.admiral.adapter.docker.service.DockerAdapterCommandExecutor.DOCKER_CONTAINER_ID_PROP_NAME;
import static com.vmware.admiral.adapter.docker.service.DockerAdapterCommandExecutor.DOCKER_CONTAINER_IMAGE_PROP_NAME;
import static com.vmware.admiral.adapter.docker.service.DockerAdapterCommandExecutor.DOCKER_CONTAINER_NAMES_PROP_NAME;
import static com.vmware.admiral.compute.ContainerHostService.SSH_HOST_KEY_PROP_NAME;
import static com.vmware.admiral.compute.ContainerHostService.SSL_TRUST_ALIAS_PROP_NAME;
import static com.vmware.admiral.compute.ContainerHostService.SSL_TRUST_CERT_PROP_NAME;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.vmware.admiral.adapter.common.ContainerHostOperationType;
import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.SshUntrustedServerException;
import com.vmware.admiral.common.UntrustedServerException;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.HostContainerListDataCollection.ContainerListCallback;
import com.vmware.admiral.compute.container.ShellContainerExecutorService;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.CompletionHandler;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.AuthCredentialsService.AuthCredentialsServiceState;

/**
 * Service for fulfilling ContainerHostRequest backed by a docker server
 */
public class DockerHostAdapterService extends AbstractDockerAdapterService {
    public static final String SELF_LINK = ManagementUriParts.ADAPTER_DOCKER_HOST;

    // column 7 is "Available memory", that value might changed in case migration to newer PhotonOS/Alpine
    private static final String COMMAND_AVAILABLE_MEMORY = "free -b | awk '/^Mem:/{print $7}'";
    private static final String COMMAND_CPU_USAGE = "awk -v a=\"$(awk '/cpu /{print $2+$4,$2+$4+$5}' /proc/stat; sleep 1)\" '/cpu /{split(a,b,\" \"); print 100*($2+$4-b[1])/($2+$4+$5-b[2])}'  /proc/stat";
    private static final String HIDDEN_CUSTOM_PROPERTY_PREFIX = "__";

    @Override
    public void handlePatch(Operation op) {
        ContainerHostRequest request = op.getBody(ContainerHostRequest.class);
        request.validate();

        logFine("Processing host operation request %s", request.getRequestTrackingLog());

        if (ContainerHostOperationType.PING == request.getOperationType()
                && ComputeService.FACTORY_LINK.equals(request.resourceReference.getPath())) {
            ComputeState hostComputeState = new ComputeState();
            hostComputeState.customProperties = request.customProperties;
            directPing(request, op, hostComputeState);
        } else if (ContainerHostOperationType.LIST_CONTAINERS == request.getOperationType()
                && request.serviceTaskCallback.isEmpty()) {
            getContainerHost(request, op, request.resourceReference,
                    (computeState, commandInput) ->
                    directListContainers(request, op, computeState, commandInput));
        } else {
            getContainerHost(request, op, request.resourceReference,
                    (computeState, commandInput) ->
                    processOperation(request, computeState, commandInput));
            op.complete();
        }
    }

    private void processOperation(ContainerHostRequest request, ComputeState computeState,
            CommandInput commandInput) {
        switch (request.getOperationType()) {
        case VERSION:
            doVersion(request, computeState, commandInput);
            break;
        case INFO:
            doInfo(request, computeState, commandInput);
            break;
        case PING:
            doPing(request, computeState, commandInput);
            break;
        case LIST_CONTAINERS:
            doListContainers(request, computeState, commandInput);
            break;
        case STATS:
            doStats(request, computeState);
            break;
        default:
        }
    }

    private void doVersion(ContainerHostRequest request, ComputeState computeState,
            CommandInput commandInput) {
        getCommandExecutor(computeState).hostVersion(commandInput,
                getHostPatchCompletionHandler(request));
    }

    private void doInfo(ContainerHostRequest request, ComputeState computeState,
            CommandInput commandInput) {
        getCommandExecutor(computeState).hostInfo(commandInput,
                getHostPatchCompletionHandler(request));
    }

    private void doStats(ContainerHostRequest request, ComputeState computeState) {

        String hostLink = computeState.documentSelfLink;

        Operation post = Operation.createPost(this, ShellContainerExecutorService.SELF_LINK)
                .setContextId(request.getRequestId());
        post.setUri(UriUtils.appendQueryParam(post.getUri(),
                ShellContainerExecutorService.HOST_LINK_URI_PARAM, hostLink));

        HashMap<String, Object> command = new HashMap<>();
        command.put(ShellContainerExecutorService.COMMAND_KEY,
                ShellContainerExecutorService.buildComplexCommand(
                        COMMAND_AVAILABLE_MEMORY,
                        COMMAND_CPU_USAGE));
        post.setBody(command);

        sendRequest(post.setCompletion((o2, ex2) -> {
            if (ex2 != null) {
                // We should not fail if the command does not succeed
                logSevere(Utils.toString(ex2));
                patchTaskStage(request, TaskStage.FINISHED, null);
                return;
            }

            String commandOutput = o2.getBody(String.class);
            Map<String, Object> properties = parseStatsOutput(commandOutput, hostLink);

            Operation op = Operation.createPatch(null).setBody(properties);
            getHostPatchCompletionHandler(request).handle(op, null);
        }));
    }

    private Map<String, Object> parseStatsOutput(String commandOutput, String hostLink) {
        Map<String, Object> properties = new HashMap<>();

        try {
            if (commandOutput != null) {
                String[] results = commandOutput.split("\n");

                if (results.length == 2) {
                    properties.put(
                            ContainerHostService.DOCKER_HOST_AVAILABLE_MEMORY_PROP_NAME,
                            results[0].trim());

                    properties.put(
                            ContainerHostService.DOCKER_HOST_CPU_USAGE_PCT_PROP_NAME,
                            Double.valueOf(results[1].trim()));
                } else {
                    logWarning("Unexpected stats output host [%s], output [%s]",
                            hostLink, commandOutput);
                }
            }
        } catch (Exception e) {
            logWarning("Error parsing host [%s] stats, output [%s], error : %s",
                    hostLink, commandOutput, e.getMessage());
        }

        return properties;
    }

    private void doPing(ContainerHostRequest request, ComputeState computeState,
            CommandInput commandInput) {

        updateSslTrust(request, commandInput);

        getCommandExecutor(computeState).hostPing(commandInput, (o, ex) -> {
            if (ex != null) {
                fail(request, o, ex);
            } else {
                patchTaskStage(request, TaskStage.FINISHED, null);
            }
        });
    }

    private void doListContainers(ContainerHostRequest request, ComputeState computeState,
            CommandInput commandInput) {

        updateSslTrust(request, commandInput);

        getCommandExecutor(computeState).listContainers(
                commandInput,
                (o, ex) -> {
                    if (ex != null) {
                        fail(request, o, ex);

                    } else {
                        ContainerListCallback callbackResponse = createContainerListCallback(
                                computeState, o);

                        if (Logger.getLogger(this.getClass().getName()).isLoggable(Level.FINE)) {
                            logFine("Collection returned container IDs: %s %s",
                                    callbackResponse.containerIdsAndNames.keySet().stream()
                                            .collect(Collectors.toList()),
                                    request.getRequestTrackingLog());
                        }

                        patchTaskStage(request, TaskStage.FINISHED, null, callbackResponse);
                    }
                });
    }

    // get containers within the current operation without using callback
    private void directListContainers(ContainerHostRequest request, Operation op,
            ComputeState computeState, CommandInput commandInput) {
        updateSslTrust(request, commandInput);

        getCommandExecutor(computeState).listContainers(
                commandInput,
                (o, ex) -> {
                    if (ex != null) {
                        op.fail(ex);
                    } else {
                        ContainerListCallback callbackResponse = createContainerListCallback(
                                computeState, o);
                        if (Logger.getLogger(this.getClass().getName()).isLoggable(Level.FINE)) {
                            logFine("Collection returned container IDs: %s %s",
                                    callbackResponse.containerIdsAndNames.keySet().stream()
                                            .collect(Collectors.toList()),
                                    request.getRequestTrackingLog());
                        }
                        op.setBody(callbackResponse);
                        op.complete();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private ContainerListCallback createContainerListCallback(ComputeState computeState,
            Operation o) {
        List<Map<String, Object>> containerList = o.getBody(List.class);

        ContainerListCallback callbackResponse = new ContainerListCallback();
        callbackResponse.containerHostLink = computeState.documentSelfLink;

        for (Map<String, Object> containerData : containerList) {
            String id = (String) containerData.get(DOCKER_CONTAINER_ID_PROP_NAME);
            String[] names;
            Object namesProperty = containerData
                    .get(DOCKER_CONTAINER_NAMES_PROP_NAME);
            if (namesProperty instanceof List) {
                names = ((List<String>) namesProperty)
                        .toArray(new String[0]);
            } else {
                names = (String[]) namesProperty;
            }
            callbackResponse.addIdAndNames(id, names);
            callbackResponse.containerIdsAndImage.put(id,
                    (String) containerData.get(DOCKER_CONTAINER_IMAGE_PROP_NAME));
        }
        return callbackResponse;
    }

    private void patchHostState(ContainerHostRequest request, Map<String, Object> properties,
            CompletionHandler callback) {
        ComputeState computeState = new ComputeState();

        if (properties != null && !properties.isEmpty()) {
            computeState.customProperties = new HashMap<>();

            properties.entrySet().stream()
                    .forEach(entry -> {
                        if (!entry.getKey().startsWith(HIDDEN_CUSTOM_PROPERTY_PREFIX)) {
                            computeState.customProperties.put(
                                    HIDDEN_CUSTOM_PROPERTY_PREFIX + entry.getKey(),
                                    Utils.toJson(entry.getValue()));
                        } else {
                            computeState.customProperties.put(entry.getKey(),
                                    Utils.toJson(entry.getValue()));
                        }
                    });

            computeState.customProperties.remove(
                    ContainerHostService.NUMBER_OF_CONTAINERS_PER_HOST_PROP_NAME);
        }

        sendRequest(Operation
                .createPatch(request.resourceReference)
                .setBody(computeState)
                .setCompletion(callback));
    }

    private CompletionHandler getHostPatchCompletionHandler(ContainerHostRequest request) {
        return (o, ex) -> {
            if (ex != null) {
                fail(request, o, ex);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = o.getBody(Map.class);
                patchHostState(request, properties,
                        (o1, ex1) -> patchTaskStage(request, TaskStage.FINISHED, ex1));
            }
        };
    }

    private void directPing(ContainerHostRequest request, Operation op,
            ComputeState hostComputeState) {

        try {
            String credentialsLink = getAuthCredentialLink(hostComputeState);
            if (credentialsLink == null) {
                directPing(request, op, hostComputeState, null);
            } else {
                sendRequest(Operation
                        .createGet(this, credentialsLink)
                        .setCompletion(
                                (o, ex) -> {
                                    if (ex != null) {
                                        op.fail(ex);
                                    } else {
                                        try {
                                            AuthCredentialsServiceState authCredentialsState =
                                                    o.getBody(AuthCredentialsServiceState.class);
                                            directPing(request, op, hostComputeState,
                                                    authCredentialsState);
                                        } catch (Throwable eInner) {
                                            op.fail(eInner);
                                        }
                                    }
                                }));
            }
        } catch (Throwable e) {
            op.fail(e);
        }
    }

    private void directPing(ContainerHostRequest request, Operation op,
            ComputeState hostComputeState, AuthCredentialsServiceState authCredentialsState) {
        URI dockerUri = ContainerDescription.getDockerHostUri(hostComputeState);
        CommandInput commandInput = new CommandInput().withDockerUri(dockerUri);

        if (authCredentialsState != null) {
            commandInput.withCredentials(authCredentialsState);
        }

        String sshHostKey = hostComputeState.customProperties.get(SSH_HOST_KEY_PROP_NAME);
        commandInput.withProperty(SSH_HOST_KEY_PROP_NAME, sshHostKey);

        updateSslTrust(request, commandInput);
        getCommandExecutor(hostComputeState)
                .hostPing(
                        commandInput,
                        (currentOpr, currentEx) -> {
                            if (currentEx instanceof SshUntrustedServerException) {
                                UntrustedServerException usex =
                                        (UntrustedServerException) currentEx;
                                op.setStatusCode(HttpURLConnection.HTTP_BAD_GATEWAY);
                                op.setBody(usex.getIdentification());
                                op.complete();

                            } else if (currentEx != null) {
                                op.fail(currentEx);
                            } else {
                                op.complete();
                            }
                        });
    }

    private void updateSslTrust(ContainerHostRequest request, CommandInput commandInput) {
        if (request.customProperties == null) {
            request.customProperties = new HashMap<>();
        }
        commandInput.withProperty(SSL_TRUST_CERT_PROP_NAME,
                request.customProperties.get(SSL_TRUST_CERT_PROP_NAME));

        commandInput.withProperty(SSL_TRUST_ALIAS_PROP_NAME,
                request.customProperties.get(SSL_TRUST_ALIAS_PROP_NAME));
    }

}