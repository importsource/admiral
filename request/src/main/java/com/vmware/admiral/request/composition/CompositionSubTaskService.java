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

package com.vmware.admiral.request.composition;

import static com.vmware.admiral.common.util.AssertUtil.assertNotEmpty;
import static com.vmware.admiral.common.util.PropertyUtils.mergeCustomProperties;
import static com.vmware.admiral.common.util.PropertyUtils.mergeLists;
import static com.vmware.admiral.common.util.PropertyUtils.mergeProperty;
import static com.vmware.admiral.request.utils.RequestUtils.FIELD_NAME_ALLOCATION_REQUEST;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.vmware.admiral.common.util.PropertyUtils;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.compute.BindingEvaluator;
import com.vmware.admiral.compute.BindingUtils;
import com.vmware.admiral.compute.ComponentDescription;
import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.CompositeComponentRegistry;
import com.vmware.admiral.compute.container.CompositeComponentRegistry.ComponentMeta;
import com.vmware.admiral.compute.container.CompositeDescriptionService.CompositeDescriptionExpanded;
import com.vmware.admiral.compute.content.Binding;
import com.vmware.admiral.request.ContainerAllocationTaskFactoryService;
import com.vmware.admiral.request.ContainerAllocationTaskService.ContainerAllocationTaskState;
import com.vmware.admiral.request.ContainerNetworkProvisionTaskService;
import com.vmware.admiral.request.ContainerNetworkProvisionTaskService.ContainerNetworkProvisionTaskState;
import com.vmware.admiral.request.RequestBrokerFactoryService;
import com.vmware.admiral.request.RequestBrokerService.RequestBrokerState;
import com.vmware.admiral.request.composition.CompositionSubTaskService.CompositionSubTaskState.SubStage;
import com.vmware.admiral.request.compute.ComputeProvisionTaskService;
import com.vmware.admiral.request.compute.ComputeProvisionTaskService.ComputeProvisionTaskState;
import com.vmware.admiral.service.common.AbstractTaskStatefulService;
import com.vmware.admiral.service.common.ServiceTaskCallback;
import com.vmware.admiral.service.common.ServiceTaskCallback.ServiceTaskCallbackResponse;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.ServiceUriPaths;

/**
 * Task service tracking the progress of parallel progressing composition tasks. CompositionSubTask
 * could be start executing immediately if they don't have dependency on other CompositionSubTask.
 * Otherwise, wait until all dependent on tasks completes.
 */
public class CompositionSubTaskService extends
        AbstractTaskStatefulService<CompositionSubTaskService.CompositionSubTaskState, CompositionSubTaskService.CompositionSubTaskState.SubStage> {

    public static final String DISPLAY_NAME = "Composition Component";
    public static final String ALLOC_SUFFIX = "-alloc";
    public static final String DESCRIPTION_LINK_FIELD_NAME = "descriptionLink";

    public static class CompositionSubTaskState extends
            com.vmware.admiral.service.common.TaskServiceDocument<CompositionSubTaskState.SubStage> {

        public static enum SubStage {
            CREATED,
            ALLOCATING,
            ALLOCATED,
            NOTIFY,
            PREPARE_EXECUTE,
            EXECUTE,
            EXECUTING,
            COMPLETED,
            ERROR;

            static final Set<SubStage> TRANSIENT_SUB_STAGES = new HashSet<>(
                    Arrays.asList(ALLOCATING, EXECUTING));

        }

        public String compositeDescriptionLink;

        /** (Required) The description that defines the requested resource. */
        public String resourceDescriptionLink;

        /** (Required) Type of resource to create. */
        public String resourceType;

        /** The unique name per context that defines the requested resource. */
        public String name;

        /** Flag indicating that it is only allocation request */
        public boolean allocationRequest;

        /**
         * The the list of task links that this task depends on and can't start before those tasks
         * complete.
         */
        public Set<String> dependsOnLinks;

        /**
         * Link to the current completed task that this task is dependent on. Patch parameter only.
         */
        public String currentDependsOnLink;

        /**
         * The the list of task links that depends on the completion of the current task. All of
         * those tasks will be patched once the current task completes.
         */
        public Set<String> dependentLinks;

        /** The current composition request Id transferred as context through the tasks */
        public String requestId;

        // Set by internally
        /** Set by the Task with the links of the provisioned resources. */
        public List<String> resourceLinks;

        /** Set by Task. Error count of the dependent tasks. */
        public long errorCount;

        /** Set by Task. Indicating that it is in the second phase after allocation */
        public boolean postAllocation;
    }

    public CompositionSubTaskService() {
        super(CompositionSubTaskState.class, SubStage.class, DISPLAY_NAME);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
        super.transientSubStages = SubStage.TRANSIENT_SUB_STAGES;
    }

    @Override
    protected boolean validateStageTransitionAndState(Operation patch,
            CompositionSubTaskState patchBody, CompositionSubTaskState currentState) {
        if (currentState.taskSubStage.ordinal() > patchBody.taskSubStage.ordinal()
                && patchBody.taskSubStage == SubStage.EXECUTE) {
            // ignore out-of-order EXECUTE from parent task: task was moved to EXECUTE by a
            // dependent task
            logFine("Ignoring subStage move from %s(%s) to %s(%s). Caller: [%s]",
                    currentState.taskInfo.stage, currentState.taskSubStage,
                    patchBody.taskInfo.stage, patchBody.taskSubStage, patch.getReferer());
            patch.complete();
            return true;
        }
        return super.validateStageTransitionAndState(patch, patchBody, currentState);
    }

    @Override
    protected void handleStartedStagePatch(CompositionSubTaskState state) {
        switch (state.taskSubStage) {
        case CREATED:
            checkDependencies(state);
            break;
        case ALLOCATING:
            break;
        case ALLOCATED:
            notifyDependentTasks(state, SubStage.CREATED, () -> {
                if (state.allocationRequest) {
                    sendSelfPatch(createUpdateSubStageTask(state, SubStage.NOTIFY));
                } else {
                    CompositionSubTaskState body = createUpdateSubStageTask(state,
                            SubStage.COMPLETED);
                    body.taskInfo.stage = TaskStage.FINISHED;
                    sendSelfPatch(body);
                }
            });
            break;
        case NOTIFY:
            notifyCaller(state);
            break;
        case PREPARE_EXECUTE:
            break;
        case EXECUTE:
            checkDependencies(state);
            break;
        case EXECUTING:
            break;
        case COMPLETED:
            notifyDependentTasks(state, SubStage.EXECUTE, () -> {
                CompositionSubTaskState body = createUpdateSubStageTask(state,
                        SubStage.COMPLETED);
                body.taskInfo.stage = TaskStage.FINISHED;
                sendSelfPatch(body);
            });
            break;
        case ERROR:
            if (!hasDependencies(state)) {
                notifyDependentTasks(state, SubStage.ERROR, () -> {
                    CompositionSubTaskState body = createUpdateSubStageTask(state, SubStage.ERROR);
                    body.taskInfo.stage = TaskStage.FAILED;
                    sendSelfPatch(body);
                });
            }
            break;
        default:
            break;
        }
    }

    private void notifyCaller(CompositionSubTaskState state) {
        ServiceTaskCallbackResponse callbackResponse = getFinishedCallbackResponse(state);
        callbackResponse.customProperties = mergeCustomProperties(
                callbackResponse.customProperties, state.customProperties);
        sendRequest(Operation.createPatch(this, state.serviceTaskCallback.serviceSelfLink)
                .setBody(callbackResponse)
                // Pragma needed because the service might be still in creation state (asynch
                // creation)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        logWarning("Notifying parent task %s from composition failed: %s",
                                o.getUri(), Utils.toString(e));
                    }
                }));
    }

    @Override
    protected ServiceTaskCallbackResponse getFinishedCallbackResponse(
            CompositionSubTaskState state) {
        if (state.resourceLinks == null || state.resourceLinks.isEmpty()) {
            String errMsg = "No resourceLinks found for allocated resources.";
            logWarning(errMsg);
            return state.serviceTaskCallback.getFailedResponse(new IllegalStateException(errMsg));
        } else {
            CallbackCompleteResponse finishedResponse = new CallbackCompleteResponse();
            finishedResponse.copy(state.serviceTaskCallback.getFinishedResponse());
            finishedResponse.resourceLinks = state.resourceLinks;
            return finishedResponse;
        }
    }

    protected static class CallbackCompleteResponse extends ServiceTaskCallbackResponse {
        List<String> resourceLinks;
    }

    @Override
    protected void validateStateOnStart(CompositionSubTaskState state)
            throws IllegalArgumentException {
        assertNotEmpty(state.name, "name");
        assertNotEmpty(state.resourceDescriptionLink, "resourceDescriptionLink");
        assertNotEmpty(state.resourceType, "resourceType");
    }

    @Override
    protected boolean validateStageTransition(Operation patch, CompositionSubTaskState patchBody,
            CompositionSubTaskState currentState) {
        currentState.resourceLinks = mergeLists(currentState.resourceLinks,
                patchBody.resourceLinks);

        if (SubStage.PREPARE_EXECUTE == patchBody.taskSubStage) {
            currentState.dependsOnLinks = mergeProperty(currentState.dependsOnLinks,
                    patchBody.dependsOnLinks);
            currentState.serviceTaskCallback = mergeProperty(currentState.serviceTaskCallback,
                    patchBody.serviceTaskCallback);
        }

        if (patchBody.currentDependsOnLink != null && currentState.dependsOnLinks != null) {
            boolean removed = currentState.dependsOnLinks.remove(patchBody.currentDependsOnLink);
            if (removed) {
                logFine("Completion of depends on task [%s] patched.",
                        patchBody.currentDependsOnLink);
            } else {
                logWarning("Completion of depends on task [%s] patched but not found in the list.",
                        patchBody.currentDependsOnLink);
            }
        }

        if (TaskStage.STARTED == patchBody.taskInfo.stage
                && SubStage.ERROR == patchBody.taskSubStage) {
            if (hasDependencies(currentState)) {
                currentState.errorCount = currentState.errorCount + 1;
                currentState.taskSubStage = currentState.postAllocation ? SubStage.EXECUTE
                        : SubStage.CREATED;
            }
        }

        if (SubStage.PREPARE_EXECUTE == patchBody.taskSubStage) {
            currentState.postAllocation = true; // second phase of provisioning
        }

        return false;
    }

    private void notifyDependentTasks(final CompositionSubTaskState state,
            final SubStage taskSubStage, final Runnable callback) {
        if (state.dependentLinks == null || state.dependentLinks.isEmpty()) {
            logFine("No dependent task to notify for completion.");
            callback.run();
            return;
        }

        final AtomicInteger countDown = new AtomicInteger(state.dependentLinks.size());
        final AtomicBoolean error = new AtomicBoolean();
        for (final String dependentTaskLink : state.dependentLinks) {
            final CompositionSubTaskState task = new CompositionSubTaskState();
            task.currentDependsOnLink = state.documentSelfLink;
            task.taskInfo = state.taskInfo;
            task.taskSubStage = taskSubStage;
            sendRequest(Operation.createPatch(this, dependentTaskLink)
                    .setBody(task)
                    .setContextId(state.requestId)
                    .setCompletion((o, e) -> {
                        if (e != null) {
                            if (error.compareAndSet(false, true)) {
                                failTask("Failure patching dependent task: "
                                        + dependentTaskLink, e);
                            } else {
                                logWarning(// task already failed
                                        "Failure patching dependent task: [%s]. Error: %s",
                                        dependentTaskLink, Utils.toString(e));
                            }
                            return;
                        }
                        if (countDown.decrementAndGet() == 0 && !error.get()) {
                            callback.run();
                        }
                    }));
        }
    }

    private void allocate(CompositionSubTaskState state) {
        RequestBrokerState requestBrokerState = new RequestBrokerState();
        requestBrokerState.documentSelfLink = state.allocationRequest ? getSelfId() + ALLOC_SUFFIX
                : getSelfId();
        requestBrokerState.serviceTaskCallback = ServiceTaskCallback.create(state.documentSelfLink,
                TaskStage.STARTED, SubStage.ALLOCATED, TaskStage.STARTED, SubStage.ERROR);
        requestBrokerState.resourceDescriptionLink = state.resourceDescriptionLink;
        requestBrokerState.resourceType = state.resourceType;
        requestBrokerState.operation = RequestBrokerState.PROVISION_RESOURCE_OPERATION;
        requestBrokerState.tenantLinks = state.tenantLinks;
        requestBrokerState.requestTrackerLink = state.requestTrackerLink;
        requestBrokerState.customProperties = state.customProperties;
        if (state.allocationRequest) {
            if (requestBrokerState.customProperties == null) {
                requestBrokerState.customProperties = new HashMap<>();
            }
            requestBrokerState.customProperties.put(FIELD_NAME_ALLOCATION_REQUEST,
                    Boolean.TRUE.toString());
        }

        sendRequest(Operation.createPost(this, RequestBrokerFactoryService.SELF_LINK)
                .setBody(requestBrokerState)
                .setContextId(state.requestId)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Failure creating request broker task", e);
                        return;
                    }
                }));

        sendSelfPatch(createUpdateSubStageTask(state, SubStage.ALLOCATING));
    }

    private void executeTask(CompositionSubTaskState state) {
        if (ResourceType.CONTAINER_TYPE.getName().equalsIgnoreCase(state.resourceType)) {
            createContainerAllocationTaskState(state);
        } else if (ResourceType.NETWORK_TYPE.getName().equalsIgnoreCase(state.resourceType)) {
            createContainerNetworkProvisionTaskState(state);
        } else if (ResourceType.COMPUTE_TYPE.getName().equalsIgnoreCase(state.resourceType)) {
            createComputeProvisionTaskState(state);
        } else {
            throw new IllegalArgumentException(String.format("Unsupported type. Must be: %s or %s",
                    ResourceType.CONTAINER_TYPE, ResourceType.COMPUTE_TYPE));
        }
    }

    private void createContainerAllocationTaskState(CompositionSubTaskState state) {
        ContainerAllocationTaskState allocationTask = new ContainerAllocationTaskState();
        allocationTask.documentSelfLink = getSelfId();
        allocationTask.serviceTaskCallback = ServiceTaskCallback.create(state.documentSelfLink,
                TaskStage.STARTED, SubStage.COMPLETED, TaskStage.STARTED, SubStage.ERROR);
        allocationTask.customProperties = state.customProperties;
        allocationTask.resourceDescriptionLink = state.resourceDescriptionLink;
        allocationTask.resourceCount = Long.valueOf(state.resourceLinks.size());
        allocationTask.resourceType = state.resourceType;
        allocationTask.tenantLinks = state.tenantLinks;
        allocationTask.requestTrackerLink = state.requestTrackerLink;
        allocationTask.resourceLinks = state.resourceLinks;
        allocationTask.postAllocation = state.postAllocation;

        sendRequest(Operation.createPost(this, ContainerAllocationTaskFactoryService.SELF_LINK)
                .setBody(allocationTask)
                .setContextId(state.requestId)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Failure creating container allocation task", e);
                        return;
                    }
                }));

        sendSelfPatch(createUpdateSubStageTask(state, SubStage.EXECUTING));
    }

    private void createContainerNetworkProvisionTaskState(CompositionSubTaskState state) {
        ContainerNetworkProvisionTaskState task = new ContainerNetworkProvisionTaskState();
        task.documentSelfLink = getSelfId();
        task.serviceTaskCallback = ServiceTaskCallback.create(state.documentSelfLink,
                TaskStage.STARTED, SubStage.COMPLETED, TaskStage.STARTED, SubStage.ERROR);
        task.customProperties = state.customProperties;
        task.resourceCount = Long.valueOf(state.resourceLinks.size());
        task.resourceType = state.resourceType;
        task.tenantLinks = state.tenantLinks;
        task.requestTrackerLink = state.requestTrackerLink;
        task.resourceLinks = state.resourceLinks;
        task.resourceDescriptionLink = state.resourceDescriptionLink;

        sendRequest(Operation.createPost(this, ContainerNetworkProvisionTaskService.FACTORY_LINK)
                .setBody(task)
                .setContextId(state.requestId)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Failure creating container network provision task", e);
                        return;
                    }
                }));

        sendSelfPatch(createUpdateSubStageTask(state, SubStage.EXECUTING));
    }

    private void createComputeProvisionTaskState(CompositionSubTaskState state) {
        ComputeProvisionTaskState ps = new ComputeProvisionTaskState();
        ps.documentSelfLink = Service.getId(state.documentSelfLink);
        ps.serviceTaskCallback = ServiceTaskCallback.create(state.documentSelfLink,
                TaskStage.STARTED, SubStage.COMPLETED, TaskStage.STARTED, SubStage.ERROR);
        ps.customProperties = state.customProperties;
        ps.tenantLinks = state.tenantLinks;
        ps.requestTrackerLink = state.requestTrackerLink;
        ps.resourceLinks = state.resourceLinks;

        sendRequest(Operation
                .createPost(this, ComputeProvisionTaskService.FACTORY_LINK)
                .setBody(ps)
                .setContextId(state.requestId)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Failure creating compute provision task", e);
                        return;
                    }
                }));

        sendSelfPatch(createUpdateSubStageTask(state, SubStage.EXECUTING));
    }

    private void checkDependencies(CompositionSubTaskState state) {
        if (!hasDependencies(state)) {
            if (state.errorCount > 0) {
                sendSelfPatch(createUpdateSubStageTask(state, SubStage.ERROR));
            } else {
                if (SubStage.ALLOCATING.ordinal() > state.taskSubStage.ordinal()) {
                    allocate(state);
                } else {
                    evaluateBindings(state.compositeDescriptionLink, state.resourceDescriptionLink,
                            () -> executeTask(state));
                }
            }

        }
    }

    private void evaluateBindings(String compositeDescriptionLink,
            String resourceDescriptionLink, Runnable callback) {

        URI uri = UriUtils.buildUri(this.getHost(), compositeDescriptionLink);
        URI expandUri = UriUtils.extendUriWithQuery(uri, UriUtils.URI_PARAM_ODATA_EXPAND,
                Boolean.TRUE.toString());
        Operation.createGet(expandUri).setCompletion((o, e) -> {
            if (e != null) {
                failTask("Error retrieving composite description with link "
                        + compositeDescriptionLink, e);
                return;
            }

            CompositeDescriptionExpanded compositeDescription = o
                    .getBody(CompositeDescriptionExpanded.class);

            if (compositeDescription.bindings == null) {
                callback.run();
                return;
            }

            ComponentDescription description = compositeDescription.componentDescriptions.stream()
                    .filter(c -> c.component.documentSelfLink.equals(resourceDescriptionLink))
                    .findFirst().get();

            List<Binding> provisioningTimeBindings = description.bindings.stream()
                    .filter(b -> b.isProvisioningTimeBinding()).collect(Collectors.toList());

            if (provisioningTimeBindings.isEmpty()) {
                callback.run();
                return;
            }

            Set<String> dependsOnDescriptionLinks = new HashSet<>();

            Map<String, ComponentDescription> nameToComponent = compositeDescription.componentDescriptions
                    .stream()
                    .collect(Collectors.toMap(c -> c.name, c -> c));

            for (Binding binding : provisioningTimeBindings) {
                String sourceComponentName = BindingUtils
                        .extractComponentNameFromBindingExpression(
                                binding.placeholder.bindingExpression);
                ComponentDescription sourceDescription = nameToComponent.get(sourceComponentName);

                dependsOnDescriptionLinks.add(sourceDescription.component.documentSelfLink);
            }

            getDependsOnProvisionedResources(compositeDescription, dependsOnDescriptionLinks,
                    description.component.documentSelfLink, provisioningTimeBindings, callback);

        }).sendWith(this);
    }

    private void getDependsOnProvisionedResources(
            CompositeDescriptionExpanded compositeDescription,
            Set<String> dependsOnDescriptionLinks, String descLink,
            List<Binding> provisioningTimeBindings, Runnable callback) {
        QueryTask componentDescriptionQueryTask = new QueryTask();
        componentDescriptionQueryTask.querySpec = new QueryTask.QuerySpecification();
        componentDescriptionQueryTask.taskInfo.isDirect = true;
        componentDescriptionQueryTask.documentExpirationTimeMicros = ServiceDocumentQuery
                .getDefaultQueryExpiration();

        QueryUtil.addExpandOption(componentDescriptionQueryTask);

        dependsOnDescriptionLinks.add(descLink);
        QueryUtil.addListValueClause(componentDescriptionQueryTask,
                DESCRIPTION_LINK_FIELD_NAME,
                dependsOnDescriptionLinks);

        Map<String, ComponentDescription> selfLinkToComponent = compositeDescription.componentDescriptions
                .stream()
                .collect(Collectors.toMap(c -> c.component.documentSelfLink, c -> c));

        // TODO Is this enough to get _only_ the provisioned stuff we need? ContainerStates have a
        // contextId, but ComputeStates don't. Descriptions are cloned, so it looks like this should
        // be enough
        Operation.createPost(this, ServiceUriPaths.CORE_QUERY_TASKS)
                .setBody(componentDescriptionQueryTask)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Error getting provisioned resources while evaluating bindings",
                                e);
                    } else {
                        try {
                            QueryTask resultTask = o.getBody(QueryTask.class);
                            ServiceDocumentQueryResult result = resultTask.results;

                            if (result == null || result.documents == null) {
                                callback.run();
                                return;
                            }

                            Map<String, Object> provisionedResources = new HashMap<>();
                            Map<String, Object> statesToUpdate = new HashMap<>();
                            result.documents.forEach((link, document) -> {
                                ComponentMeta meta = CompositeComponentRegistry
                                        .metaByStateLink(link);
                                if (meta != null) {
                                    ResourceState state = Utils.fromJson(document, meta.stateClass);
                                    String descriptionLink = PropertyUtils.getValue(state,
                                            DESCRIPTION_LINK_FIELD_NAME);
                                    ComponentDescription componentDescription = selfLinkToComponent
                                            .get(descriptionLink);
                                    provisionedResources.put(componentDescription.name, state);

                                    if (descLink.equals(descriptionLink)) {
                                        statesToUpdate.put(link, state);
                                    }
                                } else {
                                    logWarning("Unexpected result type: %s", link);
                                }
                            });

                            List<Operation> updates = new ArrayList<>();
                            for (Map.Entry<String, Object> entry : statesToUpdate.entrySet()) {
                                Object evaluated = BindingEvaluator
                                        .evaluateProvisioningTimeBindings(entry.getValue(),
                                                provisioningTimeBindings, provisionedResources);
                                updates.add(
                                        Operation.createPut(this, entry.getKey())
                                                .setBody(evaluated));
                            }

                            if (!updates.isEmpty()) {
                                OperationJoin.create(updates).setCompletion((ops, exs) -> {
                                    if (exs != null && exs.size() > 0) {
                                        failTask(
                                                "Failure updating evaluated state",
                                                exs.values().iterator().next());
                                        return;
                                    }
                                    callback.run();
                                }).sendWith(this);
                            }
                        } catch (Exception ex) {
                            failTask("Failure updating evaluated state", ex);
                        }
                    }
                }).sendWith(this);
    }

    private boolean hasDependencies(CompositionSubTaskState state) {
        return state.dependsOnLinks != null && !state.dependsOnLinks.isEmpty();
    }
}